package com.vera.pdf;

import java.util.List;

import tools.jackson.databind.JsonNode;

public record PdfValidationResponse(
		boolean compliant,
		boolean fixAttempted,
		String profile,
		String fileName,
		List<PdfRuleResult> passedRules,
		List<PdfRuleResult> failedRules,
		String fixedFilePath,
		JsonNode report) {
}
