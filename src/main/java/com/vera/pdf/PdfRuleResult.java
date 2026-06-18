package com.vera.pdf;

public record PdfRuleResult(
		String specification,
		String clause,
		String testNumber,
		String status,
		String mapping,
		String description) {
}
