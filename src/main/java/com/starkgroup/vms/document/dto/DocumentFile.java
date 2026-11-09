package com.starkgroup.vms.document.dto;

/**
 * File bytes plus the metadata needed for the download response headers.
 */
public record DocumentFile(String fileName, String contentType, byte[] content) {
}
