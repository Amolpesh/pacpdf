package com.vera.pdf;

public class PdfValidationException extends RuntimeException {

	public PdfValidationException(String message) {
		super(message);
	}

	public PdfValidationException(String message, Throwable cause) {
		super(message, cause);
	}
}
