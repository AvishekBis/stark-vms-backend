package com.starkgroup.vms.common.exception;

/**
 * Uploaded file is above the size limit (BR-07); mapped to 413 file-too-large.
 */
public class FileTooLargeException extends RuntimeException {

    public static final String MESSAGE = "The file is larger than 5 MB.";

    public FileTooLargeException() {
        super(MESSAGE);
    }
}
