package com.vera.pdf;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/pdf")
public class PdfValidationController {

	private final PdfValidationService pdfValidationService;

	public PdfValidationController(PdfValidationService pdfValidationService) {
		this.pdfValidationService = pdfValidationService;
	}

	@PostMapping(path = "/validate-fix", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public PdfValidationResponse validateAndFix(
			@RequestParam("file") MultipartFile file,
			@RequestParam(defaultValue = "true") boolean fix) {
		return pdfValidationService.validateAndFix(file, fix);
	}

	@ExceptionHandler(PdfValidationException.class)
	public ResponseEntity<Map<String, String>> handleValidationException(PdfValidationException exception) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", exception.getMessage()));
	}
}
