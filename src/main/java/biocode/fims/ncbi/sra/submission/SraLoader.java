package biocode.fims.ncbi.sra.submission;

import biocode.fims.models.User;
import biocode.fims.ncbi.models.SraMetadata;
import biocode.fims.ncbi.models.SraSubmissionData;
import biocode.fims.ncbi.models.SubmittableBioSample;
import biocode.fims.ncbi.models.submission.SraSubmission;
import biocode.fims.repositories.SraSubmissionRepository;
import biocode.fims.rest.models.SraUploadMetadata;
import biocode.fims.rest.responses.SraUploadResponse;
import biocode.fims.utils.FileUtils;
import biocode.fims.utils.StringGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author rjewing
 */
public class SraLoader {
    private final static Logger logger = LoggerFactory.getLogger(SraLoader.class);

    private final SraUploadMetadata metadata;
    private final ZipInputStream is;
    private final SraSubmissionData sraSubmissionData;
    private final String sraSubmissionDir;
    private final User user;

    private final ArrayList<String> invalidFiles;
    private final String url;
    private final SraSubmissionRepository sraSubmissionRepository;
    private Map<String, List<File>> files;
    private Path submissionDir;

    public SraLoader(SraUploadMetadata metadata, ZipInputStream is, SraSubmissionData sraSubmissionData,
                     String sraSubmissionDir, User user, String appUrl, SraSubmissionRepository sraSubmissionRepository) {
        this.metadata = metadata;
        this.is = is;
        this.sraSubmissionData = sraSubmissionData;
        this.sraSubmissionDir = sraSubmissionDir;
        this.user = user;
        this.url = appUrl;
        this.sraSubmissionRepository = sraSubmissionRepository;
        invalidFiles = new ArrayList<>();
        files = new HashMap<>();
    }

    public SraUploadResponse upload() {
        SraSubmissionData filteredSubmissionData = getSraSubmissionData();

        if (filteredSubmissionData.bioSamples.size() != metadata.bioSamples.size()) {
            return new SraUploadResponse(false, "Invalid bioSamples provided");
        }

        try {
            extractFiles();
        } catch (IOException e) {
            deleteSubmissionDir();
            return new SraUploadResponse(false, "Invalid/corrupt zip file.");
        }

        // check that all filenames are present
        List<String> missingFiles = checkForMissingFiles(filteredSubmissionData);

        if (missingFiles.size() > 0) {
            deleteSubmissionDir();
            return new SraUploadResponse(
                    false,
                    "The following required files are missing: " +
                            String.join("\", \"", missingFiles) + "\".\n " +
                            "Either submit these files, or remove the bioSamples that require these files from this submission."
            );
        }


        try {
            writeSubmissionXml(filteredSubmissionData);
        } catch (JAXBException e) {
            logger.error("Error creating submission.xml", e);
            deleteSubmissionDir();
            return new SraUploadResponse(false, "Error creating submission.xml file");
        }

        try {
            sraSubmissionRepository.save(
                    new biocode.fims.models.SraSubmission(
                            metadata.project,
                            metadata.expedition,
                            user,
                            getSubmissionDirectory()
                    )
            );
        } catch (Exception e) {
            logger.error("Error saving SraSubmission", e);
            deleteSubmissionDir();
            return new SraUploadResponse(false, "Error saving SRA submission");
        }

        // validate is from
        return new SraUploadResponse(true, null);
    }

    private void writeSubmissionXml(SraSubmissionData filteredSubmissionData) throws JAXBException {
        SraSubmission submission = new SraSubmission(filteredSubmissionData, metadata, user, url);
        JAXBContext jaxbContext = JAXBContext.newInstance(SraSubmission.class);
        Marshaller marshaller = jaxbContext.createMarshaller();
        File file = new File(getSubmissionDirectory().toString(), "submission.xml");
        marshaller.marshal(submission, file);
    }

    private List<String> checkForMissingFiles(SraSubmissionData filteredSubmissionData) {
        List<String> requiredFiles = filteredSubmissionData.sraMetadata.stream()
                .flatMap(m ->
                        Stream.of(m.get("filename"), m.get("filename2"))
                ).filter(Objects::nonNull)
                .collect(Collectors.toList());

        return requiredFiles.stream()
                .filter(name -> !files.containsKey(name))
                .collect(Collectors.toList());
    }

    private SraSubmissionData getSraSubmissionData() {
        List<SubmittableBioSample> bioSamples = sraSubmissionData.bioSamples.stream()
                .filter(b -> metadata.bioSamples.contains(b.getSampleName()))
                .collect(Collectors.toList());
        List<SraMetadata> sraMetadata = sraSubmissionData.sraMetadata.stream()
                .filter(m -> metadata.bioSamples.contains(m.get("sample_name")))
                .collect(Collectors.toList());

        return new SraSubmissionData(bioSamples, sraMetadata);
    }

    private Path getSubmissionDirectory() {
        if (this.submissionDir == null) {
            this.submissionDir = Paths.get(sraSubmissionDir, metadata.expedition.getExpeditionCode() + "_" + new Date().getTime());
            this.submissionDir.toFile().mkdir();
        }
        return this.submissionDir;
    }

    private void extractFiles() throws IOException {
        Path submissionDir = getSubmissionDirectory();

        ZipEntry ze = is.getNextEntry();

        // if root ZipEntry is a directory, extract files from there
        String zipRootDir = "";
        if (ze.isDirectory()) {
            zipRootDir = ze.getName();
            ze = is.getNextEntry();
        }

        // These suffixes are from FastqFilenamesRule
        List<String> fileSuffixes = Arrays.asList("fq", "fastq", "gz", "gzip", "bz2");

        byte[] buffer = new byte[1024];
        while (ze != null) {
            String fileName = ze.getName().replace(zipRootDir, "");
            String ext = FileUtils.getExtension(fileName, "");

            // ignore nested directories & unsupported file extensions
            if (ze.isDirectory() ||
                    fileName.split(File.separator).length > 1 ||
                    !fileSuffixes.contains(ext.toLowerCase())) {
                logger.info("ignoring dir/unsupported file: " + ze.getName());

                // don't report about hidden osx included dir
                if (!ze.getName().startsWith("__MACOSX") && !ze.getName().endsWith(".DS_Store")) {
                    invalidFiles.add(ze.getName());
                }

                if (ze.isDirectory()) {
                    // skip everything in that directory
                    String root = ze.getName();

                    do {
                        ze = is.getNextEntry();
                    }
                    while (ze != null && ze.getName().startsWith(root));

                } else {
                    ze = is.getNextEntry();
                }
                continue;
            }

            File file = new File(submissionDir.toString(), ze.getName());

            logger.debug("unzipping file: " + fileName + " to: " + file.getAbsolutePath());

            try (FileOutputStream fos = new FileOutputStream(file)) {
                int len;
                while ((len = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }

                files.computeIfAbsent(fileName, k -> new ArrayList<>()).add(file);
            } catch (Exception e) {
                logger.debug("Failed to extract file", e);
                invalidFiles.add(ze.getName());
            }

            ze = is.getNextEntry();
        }
    }

    private void deleteSubmissionDir() {
        File dir = getSubmissionDirectory().toFile();
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        dir.delete();
    }

}
