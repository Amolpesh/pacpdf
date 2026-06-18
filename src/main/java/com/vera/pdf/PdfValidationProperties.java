package com.vera.pdf;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pdf.validation")
public class PdfValidationProperties {

	private String veraPdfCommand = "F:\\vera\\setup\\verapdf.bat";

	private String veraPdfProfile = "ua2";

	private String defaultLanguage = "en-US";

	public String getVeraPdfCommand() {
		return veraPdfCommand;
	}

	public void setVeraPdfCommand(String veraPdfCommand) {
		this.veraPdfCommand = veraPdfCommand;
	}

	public String getVeraPdfProfile() {
		return veraPdfProfile;
	}

	public void setVeraPdfProfile(String veraPdfProfile) {
		this.veraPdfProfile = veraPdfProfile;
	}

	public String getDefaultLanguage() {
		return defaultLanguage;
	}

	public void setDefaultLanguage(String defaultLanguage) {
		this.defaultLanguage = defaultLanguage;
	}
}
