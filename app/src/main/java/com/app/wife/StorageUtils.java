package com.wife.app;

import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

public final class StorageUtils {
    private static final String TAG = "StorageUtils";
    private static final String ROOT_FOLDER_NAME = "wife shared";

    private StorageUtils() {}

    public static File getRootDir() {
        File rootDir = new File(Environment.getExternalStorageDirectory(), ROOT_FOLDER_NAME);
        if (!rootDir.exists()) {
            rootDir.mkdirs();
        }
        return rootDir;
    }

    public static String getSubFolder(String filename) {
        String ext = "";
        int idx = filename.lastIndexOf('.');
        if (idx > 0) {
            ext = filename.substring(idx + 1).toLowerCase(Locale.US);
        }

        switch (ext) {
            case "mp3":
            case "emv":
            case "wav":
            case "ogg":
            case "m4a":
            case "aac":
                return "music";
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "bmp":
            case "webp":
                return "images";
            case "mp4":
            case "mkv":
            case "avi":
            case "mov":
            case "3gp":
            case "webm":
                return "videos";
            case "pdf":
            case "txt":
            case "doc":
            case "docx":
            case "xls":
            case "xlsx":
            case "ppt":
            case "pptx":
                return "document";
            default:
                return "misc";
        }
    }

    public static File getTargetDirectory(String filename) {
        File rootDir = getRootDir();
        File targetDir = new File(rootDir, getSubFolder(filename));
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        return targetDir;
    }

    public static File getTargetFile(String filename) {
        return new File(getTargetDirectory(filename), filename);
    }

    public static boolean copyFile(File src, File dst) {
        WifeLogger.log(TAG, "copyFile() initiated. Source: " + src.getAbsolutePath() + " | Destination: " + dst.getAbsolutePath());
        if (!src.exists()) {
            WifeLogger.log(TAG, "copyFile() aborted: Source file does not exist.");
            return false;
        }

        File parentDir = dst.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (FileInputStream inStream = new FileInputStream(src);
             FileOutputStream outStream = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inStream.read(buffer)) != -1) {
                outStream.write(buffer, 0, bytesRead);
            }
            outStream.flush();
            WifeLogger.log(TAG, "copyFile() completed successfully. Size: " + dst.length() + " bytes");
            return true;
        } catch (IOException e) {
            WifeLogger.log(TAG, "copyFile() failed with exception: " + e.getMessage(), e);
            return false;
        }
    }
}