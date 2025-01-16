package com.sismics.docs.core.util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CountingInputStream;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.sismics.docs.core.constant.Constants;
import com.sismics.docs.core.dao.FileDao;
import com.sismics.docs.core.dao.UserDao;
import com.sismics.docs.core.event.DocumentUpdatedAsyncEvent;
import com.sismics.docs.core.event.FileCreatedAsyncEvent;
import com.sismics.docs.core.event.FileDeletedAsyncEvent;
import com.sismics.docs.core.model.context.AppContext;
import com.sismics.docs.core.model.jpa.File;
import com.sismics.docs.core.model.jpa.User;
import com.sismics.util.ImageDeskew;
import com.sismics.util.Scalr;
import com.sismics.util.context.ThreadLocalContext;
import com.sismics.util.io.InputStreamReaderThread;
import com.sismics.util.mime.MimeTypeUtil;

/**
 * File entity utilities.
 * 
 * @author bgamard
 */
public class FileUtil {
    /**
     * Logger.
     */
    private static final Logger log = LoggerFactory.getLogger(FileUtil.class);

    /**
     * File ID of files currently being processed.
     */
    private static final Set<String> processingFileSet = Collections.synchronizedSet(new HashSet<>());
    
    /**
     * Optical character recognition on an image.
     *
     * @param language Language to OCR
     * @param image Buffered image
     * @return Content extracted
     * @throws Exception e
     */
    public static String ocrFile(String language, BufferedImage image) throws Exception {
        // Upscale, grayscale and deskew the image
        BufferedImage resizedImage = Scalr.resize(image, Scalr.Method.AUTOMATIC, Scalr.Mode.AUTOMATIC, 3500, Scalr.OP_ANTIALIAS, Scalr.OP_GRAYSCALE);
        image.flush();
        ImageDeskew imageDeskew = new ImageDeskew(resizedImage);
        BufferedImage deskewedImage = Scalr.rotate(resizedImage, - imageDeskew.getSkewAngle(), Scalr.OP_ANTIALIAS, Scalr.OP_GRAYSCALE);
        resizedImage.flush();
        Path tmpFile = AppContext.getInstance().getFileService().createTemporaryFile();
        ImageIO.write(deskewedImage, "tiff", tmpFile.toFile());

        List<String> result = Lists.newLinkedList(Arrays.asList("tesseract", tmpFile.toAbsolutePath().toString(), "stdout", "-l", language));
        ProcessBuilder pb = new ProcessBuilder(result);
        Process process = pb.start();

        // Consume the process error stream
        final String commandName = pb.command().get(0);
        new InputStreamReaderThread(process.getErrorStream(), commandName).start();

        // Consume the data as text
        try (InputStream is = process.getInputStream()) {
            return CharStreams.toString(new InputStreamReader(is, StandardCharsets.UTF_8));
        }
    }

    /**
     * Remove a file from the storage filesystem.
     * 
     * @param file ID of file to delete
     */
    public static void delete(FileDeletedAsyncEvent event) throws IOException {
       File aFile = new File();
       aFile.setId(event.getFileId());
       aFile.setUserId(event.getUserId());
       aFile.setName(event.getFileName());
       delete(aFile);
    }
    /**
     * Remove a file from the storage filesystem.
     * 
     * @param file ID of file to delete
     */
    public static void delete(File file) throws IOException {
        Path storedFile = DirectoryUtil.getStorageDirectory(file).resolve(file.getName());
        Path webFile = DirectoryUtil.getStorageDirectory(file).resolve(file.getName() + "_web");
        Path thumbnailFile = DirectoryUtil.getStorageDirectory(file).resolve(file.getName() + "_thumb");
        
        if (Files.exists(storedFile)) {
            if( ConfigUtil.isFileDelete() )
              Files.delete(storedFile);
            else{
              java.nio.file.Path aDestFile = DirectoryUtil.getDeleteStorageDirectory(file).resolve(file.getName());
              storedFile.toFile().renameTo(aDestFile.toFile());
            }
        }
        if (Files.exists(webFile)) {
            if( ConfigUtil.isFileDelete() )
              Files.delete(webFile);
            else{
              java.nio.file.Path aDestFile = DirectoryUtil.getDeleteStorageDirectory(file).resolve(file.getName()+"_web");
              webFile.toFile().renameTo(aDestFile.toFile());
            }
        }
        if (Files.exists(thumbnailFile)) {
            if( ConfigUtil.isFileDelete() )
              Files.delete(thumbnailFile);
            else{
              java.nio.file.Path aDestFile = DirectoryUtil.getDeleteStorageDirectory(file).resolve(file.getName()+"_thumb");
              thumbnailFile.toFile().renameTo(aDestFile.toFile());  
            }
        }
       
        Files.delete(DirectoryUtil.getStorageDirectory(file));
        
}

    
    /**
     * Create a new file.
     *
     * @param name File name, can be null
     * @param previousFileId ID of the previous version of the file, if the new file is a new version
     * @param unencryptedFile Path to the unencrypted file
     * @param fileSize File size
     * @param language File language, can be null if associated to no document
     * @param userId User ID creating the file
     * @param documentId Associated document ID or null if no document
     * @return File ID
     * @throws Exception e
     */
    public static String createFile(String name, String previousFileId, Path unencryptedFile, long fileSize, String language, String userId, String documentId) throws Exception {
        // Validate mime type
        String mimeType;
        try {
            mimeType = MimeTypeUtil.guessMimeType(unencryptedFile, name);
        } catch (IOException e) {
            throw new IOException("ErrorGuessMime", e);
        }

        // Validate user quota
        UserDao userDao = new UserDao();
        User user = userDao.getById(userId);
        if (user.getStorageCurrent() + fileSize > user.getStorageQuota()) {
            throw new IOException("QuotaReached");
        }

        // Validate global quota
        String globalStorageQuotaStr = System.getenv(Constants.GLOBAL_QUOTA_ENV);
        if (!Strings.isNullOrEmpty(globalStorageQuotaStr)) {
            long globalStorageQuota = Long.parseLong(globalStorageQuotaStr);
            long globalStorageCurrent = userDao.getGlobalStorageCurrent();
            if (globalStorageCurrent + fileSize > globalStorageQuota) {
                throw new IOException("QuotaReached");
            }
        }

        // Prepare the file
        File file = new File();
        file.setOrder(0);
        file.setVersion(0);
        file.setLatestVersion(true);
        file.setDocumentId(documentId);
        file.setName(StringUtils.abbreviate(name, 200));
        file.setMimeType(mimeType);
        file.setUserId(userId);
        file.setSize(fileSize);

        // Get files of this document
        FileDao fileDao = new FileDao();
        if (documentId != null) {
            if (previousFileId == null) {
                // It's not a new version, so put it in last order
                file.setOrder(fileDao.getByDocumentId(userId, documentId).size());
            } else {
                // It's a new version, update the previous version
                File previousFile = fileDao.getActiveById(previousFileId);
                if (previousFile == null || !previousFile.getDocumentId().equals(documentId)) {
                    throw new IOException("Previous version mismatch");
                }

                if (previousFile.getVersionId() == null) {
                    previousFile.setVersionId(UUID.randomUUID().toString());
                }

                // Copy the previous file metadata
                file.setOrder(previousFile.getOrder());
                file.setVersionId(previousFile.getVersionId());
                file.setVersion(previousFile.getVersion() + 1);

                // Update the previous file
                previousFile.setLatestVersion(false);
                fileDao.update(previousFile);
            }
        }

        // Create the file
        String fileId = fileDao.create(file, userId);

        // Save the file
        Path path = DirectoryUtil.getStorageDirectory(file).resolve(file.getName());
        try (InputStream inputStream = EncryptionUtil.encryptInputStream( Files.newInputStream(unencryptedFile),user.getPrivateKey())) {
            Files.copy(inputStream, path);
        }

        // Update the user quota
        user.setStorageCurrent(user.getStorageCurrent() + fileSize);
        userDao.updateQuota(user);

        // Raise a new file created event and document updated event if we have a document
        startProcessingFile(fileId);
        FileCreatedAsyncEvent fileCreatedAsyncEvent = new FileCreatedAsyncEvent();
        fileCreatedAsyncEvent.setUserId(userId);
        fileCreatedAsyncEvent.setLanguage(language);
        fileCreatedAsyncEvent.setFileId(file.getId());
        fileCreatedAsyncEvent.setUnencryptedFile(unencryptedFile);
        ThreadLocalContext.get().addAsyncEvent(fileCreatedAsyncEvent);

        if (documentId != null) {
            DocumentUpdatedAsyncEvent documentUpdatedAsyncEvent = new DocumentUpdatedAsyncEvent();
            documentUpdatedAsyncEvent.setUserId(userId);
            documentUpdatedAsyncEvent.setDocumentId(documentId);
            ThreadLocalContext.get().addAsyncEvent(documentUpdatedAsyncEvent);
        }

        return fileId;
    }

    /**
     * Start processing a file.
     *
     * @param fileId File ID
     */
    public static void startProcessingFile(String fileId) {
        processingFileSet.add(fileId);
        log.info("Processing started for file: " + fileId);
    }

    /**
     * End processing a file.
     *
     * @param fileId File ID
     */
    public static void endProcessingFile(String fileId) {
        processingFileSet.remove(fileId);
        log.info("Processing ended for file: " + fileId);
    }

    /**
     * Return true if a file is currently processing.
     *
     * @param fileId File ID
     * @return True if the file is processing
     */
    public static boolean isProcessingFile(String fileId) {
        return processingFileSet.contains(fileId);
    }

    /**
     * Get the size of a file on disk.
     *
     * @param file the file id
     * @param user   the file owner
     * @return the size or -1 if something went wrong
     */
    public static long getFileSize(File file, User user) {
        // To get the size we copy the decrypted content into a null output stream
        // and count the copied byte size.
        Path storedFile = DirectoryUtil.getStorageDirectory(file).resolve(file.getName());
        if (! Files.exists(storedFile)) {
            log.debug("File does not exist " + file.getId());
            return File.UNKNOWN_SIZE;
        }
        try (InputStream fileInputStream = Files.newInputStream(storedFile);
             InputStream inputStream = EncryptionUtil.decryptInputStream(fileInputStream, user.getPrivateKey());
             CountingInputStream countingInputStream = new CountingInputStream(inputStream);
        ) {
            IOUtils.copy(countingInputStream, NullOutputStream.NULL_OUTPUT_STREAM);
            return countingInputStream.getByteCount();
        } catch (Exception e) {
            log.debug("Can't find size of file " + file.getId(), e);
            return File.UNKNOWN_SIZE;
        }
    }
}
