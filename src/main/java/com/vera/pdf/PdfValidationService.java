package com.vera.pdf;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.awt.color.ICC_Profile;
import java.awt.color.ColorSpace;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDNumberTreeNode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.COSObjectable;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PdfValidationService {

	private static final Duration VALIDATION_TIMEOUT = Duration.ofSeconds(60);
	private static final Path FIXED_OUTPUT_DIR = Paths.get("F:\\vera\\complaint");

	private final PdfValidationProperties properties;
	private final ObjectMapper objectMapper = new ObjectMapper();

	public PdfValidationService(PdfValidationProperties properties) {
		this.properties = properties;
	}

	public PdfValidationResponse validateAndFix(MultipartFile file, boolean fix) {
		if (file == null || file.isEmpty()) {
			throw new PdfValidationException("PDF file is required.");
		}
		if (!file.getOriginalFilename().toLowerCase().endsWith(".pdf")) {
			throw new PdfValidationException("Only PDF files are supported.");
		}

		Path workDir = null;
		try {
			Path baseDir = Paths.get("F:\\vera\\temp");

			if (!Files.exists(baseDir)) {
				Files.createDirectories(baseDir);
			}

			 workDir = Files.createTempDirectory(baseDir, "pdf-validation-");

			Path inputPath = workDir.resolve("input.pdf");
			Path validationPath = fix ? workDir.resolve("fixed.pdf") : inputPath;

			file.transferTo(inputPath);

			Path fixedOutputPath = null;
			if (fix) {
				applyPdfBoxRepair(inputPath, validationPath);
				fixedOutputPath = saveFixedFile(validationPath, file.getOriginalFilename());
			}

			JsonNode report = parseReport(runVeraPdf(validationPath));
			RuleSummary summary = parseRules(report);
			return new PdfValidationResponse(
					summary.failedRules().isEmpty(),
					fix,
					properties.getVeraPdfProfile(),
					file.getOriginalFilename(),
					summary.passedRules(),
					summary.failedRules(),
					fixedOutputPath == null ? null : fixedOutputPath.toString(),
					report);
		}
		catch (IOException ex) {
			throw new PdfValidationException("Unable to process PDF file.", ex);
		}
		finally {
			deleteRecursively(workDir);
		}
	}

	private Path saveFixedFile(Path fixedFile, String originalFilename) throws IOException {
		Files.createDirectories(FIXED_OUTPUT_DIR);
		Path outputPath = FIXED_OUTPUT_DIR.resolve(fixedOutputFilename(originalFilename));
		Files.copy(fixedFile, outputPath, StandardCopyOption.REPLACE_EXISTING);
		return outputPath;
	}

	private String fixedOutputFilename(String originalFilename) {
		String filename = originalFilename == null || originalFilename.isBlank() ? "fixed.pdf" : originalFilename;
		filename = Paths.get(filename).getFileName().toString();
		filename = filename.replaceAll("[^A-Za-z0-9._-]", "_");
		if (!filename.toLowerCase().endsWith(".pdf")) {
			filename = filename + ".pdf";
		}
		int extensionIndex = filename.toLowerCase().lastIndexOf(".pdf");
		return filename.substring(0, extensionIndex) + "-fixed.pdf";
	}

	private void applyPdfBoxRepair(Path inputPath, Path outputPath) {
		try (PDDocument document = Loader.loadPDF(inputPath.toFile())) {
			fixDocumentLevelIssues(document);
			fixStructureTreeIssues(document);
			fixArtifactIssues(document);
			fixTableIssues(document);
			fixImageIssues(document);
			fixListIssues(document);
			fixHeadingIssues(document);
			fixAnnotationAndFormIssues(document);
			fixFontAndTextIssues(document);
			fixColorAndPdfSyntaxIssues(document);
			rebuildStructuralParentTree(document);
			document.save(outputPath.toFile());
		}
		catch (IOException ex) {
			throw new PdfValidationException("PDFBox repair attempt failed.", ex);
		}
	}

	private void fixDocumentLevelIssues(PDDocument document) throws IOException {
		PDDocumentCatalog catalog = document.getDocumentCatalog();
		catalog.setVersion("2.0");

		PDMarkInfo markInfo = catalog.getMarkInfo();
		if (markInfo == null) {
			markInfo = new PDMarkInfo();
		}
		markInfo.setMarked(true);
		catalog.setMarkInfo(markInfo);

		if (catalog.getStructureTreeRoot() == null) {
			PDStructureTreeRoot structureTreeRoot = new PDStructureTreeRoot();
			PDStructureElement documentElement = new PDStructureElement("Document", structureTreeRoot);
			structureTreeRoot.appendKid(documentElement);
			catalog.setStructureTreeRoot(structureTreeRoot);
		}
		ensureSingleDocumentRoot(catalog.getStructureTreeRoot());

		if (catalog.getLanguage() == null || catalog.getLanguage().isBlank()) {
			catalog.setLanguage(properties.getDefaultLanguage());
		}

		PDDocumentInformation information = document.getDocumentInformation();
		if (information.getTitle() == null || information.getTitle().isBlank()) {
			information.setTitle("Untitled PDF");
		}
		if (information.getProducer() == null || information.getProducer().isBlank()) {
			information.setProducer("PDFBox");
		}

		PDViewerPreferences viewerPreferences = catalog.getViewerPreferences();
		if (viewerPreferences == null) {
			viewerPreferences = new PDViewerPreferences();
		}
		viewerPreferences.setDisplayDocTitle(true);
		catalog.setViewerPreferences(viewerPreferences);

		ensureXmpMetadata(document, information.getTitle(), catalog.getLanguage());
		ensureRoleMap(catalog.getStructureTreeRoot());
	}

	private void fixStructureTreeIssues(PDDocument document) throws IOException {
		PDStructureTreeRoot structureTreeRoot = document.getDocumentCatalog().getStructureTreeRoot();
		if (structureTreeRoot == null) {
			return;
		}

		if (structureTreeRoot.getParentTree() == null) {
			structureTreeRoot.setParentTree(new PDNumberTreeNode(COSObjectable.class));
		}
		if (structureTreeRoot.getParentTreeNextKey() < 0) {
			structureTreeRoot.setParentTreeNextKey(0);
		}

		normalizeStructureNode(structureTreeRoot, null);
		removeDirectSpanChildren(structureTreeRoot);
		removeDirectContentItemsFromDocument(structureTreeRoot);
		assignMissingStructParents(document, structureTreeRoot);
		setMissingPageEntriesForMcidElements(document, structureTreeRoot);
	}

	private void fixArtifactIssues(PDDocument document) throws IOException {
		for (PDPage page : document.getPages()) {
			wrapPageContentAsArtifact(document, page);
			PDResources resources = page.getResources();
			if (resources != null) {
				sanitizeFormXObjects(resources, new HashSet<>());
				scanXObjectsForArtifacts(resources, new HashSet<>());
			}
		}
	}

	private void wrapPageContentAsArtifact(PDDocument document, PDPage page) throws IOException {
		if (!page.hasContents()) {
			return;
		}
		PDStream repairedStream = new PDStream(document);
		try (var output = repairedStream.createOutputStream(COSName.FLATE_DECODE)) {
			ContentStreamWriter writer = new ContentStreamWriter(output);
			var streams = page.getContentStreams();
			while (streams.hasNext()) {
				writeArtifactSanitizedTokens(streams.next(), writer);
			}
		}
		page.setContents(repairedStream);
	}

	private void sanitizeFormXObjects(PDResources resources, Set<COSBase> visitedResources) throws IOException {
		if (!visitedResources.add(resources.getCOSObject())) {
			return;
		}
		for (COSName name : resources.getXObjectNames()) {
			PDXObject xObject = resources.getXObject(name);
			if (xObject instanceof PDFormXObject form) {
				rewriteContentStream(form.getContentStream());
				if (form.getResources() != null) {
					sanitizeFormXObjects(form.getResources(), visitedResources);
				}
			}
		}
	}

	private void rewriteContentStream(PDStream stream) throws IOException {
		List<Object> tokens;
		try (var input = stream.createInputStream()) {
			tokens = new PDFStreamParser(input.readAllBytes()).parse();
		}
		try (var output = stream.createOutputStream(COSName.FLATE_DECODE)) {
			writeArtifactSanitizedTokens(tokens, new ContentStreamWriter(output));
		}
	}

	private void writeArtifactSanitizedTokens(PDStream stream, ContentStreamWriter writer) throws IOException {
		List<Object> tokens;
		try (var input = stream.createInputStream()) {
			tokens = new PDFStreamParser(input.readAllBytes()).parse();
		}
		writeArtifactSanitizedTokens(tokens, writer);
	}

	private void writeArtifactSanitizedTokens(List<Object> tokens, ContentStreamWriter writer) throws IOException {
		List<Object> operands = new ArrayList<>();
		int markedContentDepth = 0;
		boolean artifactOpen = false;
		boolean textObjectOpen = false;

		for (Object token : tokens) {
			if (!(token instanceof Operator operator)) {
				operands.add(token);
				continue;
			}

			String operatorName = operator.getName();
			if ("BMC".equals(operatorName) || "BDC".equals(operatorName)) {
				if (artifactOpen) {
					writer.writeToken(Operator.getOperator("EMC"));
					artifactOpen = false;
				}
				if (markedContentDepth > 0 || isTaggedMarkedContent(operands) || isArtifactMarkedContent(operands)) {
					writeOperatorGroup(writer, operands, operator);
				}
				else {
					writer.writeToken(COSName.ARTIFACT);
					writer.writeToken(Operator.getOperator("BMC"));
				}
				operands.clear();
				markedContentDepth++;
			}
			else if ("EMC".equals(operatorName)) {
				writeOperatorGroup(writer, operands, operator);
				operands.clear();
				if (markedContentDepth > 0) {
					markedContentDepth--;
				}
			}
			else {
				if ("BT".equals(operatorName)) {
					if (markedContentDepth == 0 && !artifactOpen) {
						writer.writeToken(COSName.ARTIFACT);
						writer.writeToken(Operator.getOperator("BMC"));
						artifactOpen = true;
					}
					writeHighContrastTextColor(writer);
					writeOperatorGroup(writer, operands, operator);
					operands.clear();
					textObjectOpen = true;
					continue;
				}
				if ("ET".equals(operatorName)) {
					writeOperatorGroup(writer, operands, operator);
					operands.clear();
					textObjectOpen = false;
					if (artifactOpen) {
						writer.writeToken(Operator.getOperator("EMC"));
						artifactOpen = false;
					}
					continue;
				}
				if (markedContentDepth == 0 && !artifactOpen && !textObjectOpen) {
					writer.writeToken(COSName.ARTIFACT);
					writer.writeToken(Operator.getOperator("BMC"));
					artifactOpen = true;
				}
				writeOperatorGroup(writer, operands, operator);
				operands.clear();
			}
		}

		if (artifactOpen) {
			writer.writeToken(Operator.getOperator("EMC"));
		}
	}

	private boolean isTaggedMarkedContent(List<Object> operands) {
		if (operands.size() < 2) {
			return false;
		}
		Object properties = operands.get(1);
		if (properties instanceof COSDictionary dictionary) {
			return dictionary.getDictionaryObject(COSName.MCID) != null;
		}
		return false;
	}

	private boolean isArtifactMarkedContent(List<Object> operands) {
		return !operands.isEmpty() && COSName.ARTIFACT.equals(operands.get(0));
	}

	private boolean isTextShowingOperator(String operatorName) {
		return "Tj".equals(operatorName)
				|| "TJ".equals(operatorName)
				|| "'".equals(operatorName)
				|| "\"".equals(operatorName);
	}

	private void writeHighContrastTextColor(ContentStreamWriter writer) throws IOException {
		writer.writeToken(COSInteger.ZERO);
		writer.writeToken(Operator.getOperator("g"));
		writer.writeToken(COSInteger.ZERO);
		writer.writeToken(Operator.getOperator("G"));
	}

	private void writeOperatorGroup(ContentStreamWriter writer, List<Object> operands, Operator operator) throws IOException {
		for (Object operand : operands) {
			if (operand instanceof COSBase cosBase) {
				writer.writeToken(cosBase);
			}
		}
		writer.writeToken(operator);
	}

	private void fixTableIssues(PDDocument document) {
		PDStructureTreeRoot structureTreeRoot = document.getDocumentCatalog().getStructureTreeRoot();
		if (structureTreeRoot != null) {
			normalizeTableStructure(structureTreeRoot);
		}
	}

	private void fixImageIssues(PDDocument document) throws IOException {
		PDStructureTreeRoot structureTreeRoot = document.getDocumentCatalog().getStructureTreeRoot();
		if (structureTreeRoot != null) {
			repairFigureStructure(document, structureTreeRoot);
		}
		for (PDPage page : document.getPages()) {
			PDResources resources = page.getResources();
			if (resources != null) {
				scanImages(resources, new HashSet<>());
			}
		}
	}

	private void fixListIssues(PDDocument document) {
		PDStructureTreeRoot structureTreeRoot = document.getDocumentCatalog().getStructureTreeRoot();
		if (structureTreeRoot != null) {
			normalizeListStructure(structureTreeRoot);
		}
	}

	private void fixHeadingIssues(PDDocument document) {
		PDStructureTreeRoot structureTreeRoot = document.getDocumentCatalog().getStructureTreeRoot();
		if (structureTreeRoot != null) {
			normalizeHeadingStructure(structureTreeRoot);
			ensureHeadingBookmarks(document, structureTreeRoot);
		}
	}

	private void fixAnnotationAndFormIssues(PDDocument document) throws IOException {
		int nextStructParent = nextStructParentKey(document);
		for (PDPage page : document.getPages()) {
			if (!page.getAnnotations().isEmpty()) {
				page.getCOSObject().setName(COSName.getPDFName("Tabs"), "S");
			}
			for (PDAnnotation annotation : page.getAnnotations()) {
				String enclosingAlt = findEnclosingStructureAlt(document, annotation);
				if (enclosingAlt != null && !enclosingAlt.isBlank()
						&& !enclosingAlt.equals(annotation.getContents())) {
					annotation.setContents(enclosingAlt);
				}
				if (annotation.getStructParent() < 0) {
					annotation.setStructParent(nextStructParent++);
				}
				if (annotation instanceof PDAnnotationLink linkAnnotation) {
					ensureLinkAnnotationStructure(document, page, linkAnnotation);
				}
			}
		}
		PDStructureTreeRoot structureTreeRoot = document.getDocumentCatalog().getStructureTreeRoot();
		if (structureTreeRoot != null) {
			structureTreeRoot.setParentTreeNextKey(Math.max(structureTreeRoot.getParentTreeNextKey(), nextStructParent));
		}
	}

	private void ensureLinkAnnotationStructure(PDDocument document, PDPage page, PDAnnotationLink annotation) {
		PDStructureTreeRoot structureTreeRoot = document.getDocumentCatalog().getStructureTreeRoot();
		if (structureTreeRoot == null) {
			return;
		}
		removeAnnotationReferences(structureTreeRoot, annotation);
		if (ensurePageTextWrappedInLink(structureTreeRoot, page, annotation)) {
			return;
		}

		PDStructureElement documentElement = rootDocumentElement(structureTreeRoot);
		if (documentElement == null) {
			return;
		}

		PDStructureElement paragraph = new PDStructureElement("P", documentElement);
		paragraph.setPage(page);

		PDStructureElement link = new PDStructureElement("Link", paragraph);
		link.setPage(page);
		String linkText = annotation.getContents();
		if (linkText != null && !linkText.isBlank()) {
			link.setAlternateDescription(linkText);
		}

		PDObjectReference objectReference = new PDObjectReference();
		objectReference.setReferencedObject(annotation);
		objectReference.setPage(page);
		link.appendKid(objectReference);
		paragraph.appendKid(link);
		documentElement.appendKid(paragraph);
	}

	private boolean removeAnnotationReferences(PDStructureNode node, PDAnnotation annotation) {
		List<Object> kids = new ArrayList<>(node.getKids());
		boolean changed = false;
		for (int index = kids.size() - 1; index >= 0; index--) {
			Object kid = kids.get(index);
			if (kid instanceof PDObjectReference objectReference && referencesAnnotation(objectReference, annotation)) {
				kids.remove(index);
				changed = true;
			}
			else if (kid instanceof PDStructureElement element) {
				if (removeAnnotationReferences(element, annotation)) {
					changed = true;
				}
				if (isEmptyGeneratedAnnotationElement(element)) {
					kids.remove(index);
					changed = true;
				}
			}
		}
		if (changed) {
			node.setKids(kids);
		}
		return changed;
	}

	private boolean isEmptyGeneratedAnnotationElement(PDStructureElement element) {
		String type = effectiveStructureType(element);
		return ("Annot".equals(type) || "Link".equals(type)) && element.getKids().isEmpty();
	}

	private boolean ensurePageTextWrappedInLink(PDStructureNode node, PDPage page, PDAnnotationLink annotation) {
		for (Object kid : node.getKids()) {
			if (kid instanceof PDStructureElement element) {
				if (wrapTextContainerInLink(element, page, annotation)) {
					return true;
				}
				if (ensurePageTextWrappedInLink(element, page, annotation)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean wrapTextContainerInLink(PDStructureElement element, PDPage page, PDAnnotationLink annotation) {
		if ("Link".equals(effectiveStructureType(element)) || !isSamePage(element, page)
				|| !canContainLinkText(element) || !hasDirectNonLinkContent(element)) {
			return false;
		}

		List<Object> originalKids = new ArrayList<>(element.getKids());
		PDStructureElement link = new PDStructureElement("Link", element);
		link.setPage(page);
		String linkText = annotation.getContents();
		if (linkText != null && !linkText.isBlank()) {
			link.setAlternateDescription(linkText);
		}
		for (Object kid : originalKids) {
			appendKidPreservingObject(link, kid);
		}
		PDObjectReference objectReference = new PDObjectReference();
		objectReference.setReferencedObject(annotation);
		objectReference.setPage(page);
		link.appendKid(objectReference);
		element.setKids(List.of(link));
		return true;
	}

	private boolean isSamePage(PDStructureElement element, PDPage page) {
		return element.getPage() == null || element.getPage() == page;
	}

	private boolean canContainLinkText(PDStructureElement element) {
		String type = effectiveStructureType(element);
		return "P".equals(type) || "Span".equals(type) || "Lbl".equals(type) || "LBody".equals(type)
				|| type.startsWith("H");
	}

	private boolean hasDirectNonLinkContent(PDStructureElement element) {
		for (Object kid : element.getKids()) {
			if (kid instanceof PDStructureElement child && "Link".equals(effectiveStructureType(child))) {
				continue;
			}
			return true;
		}
		return false;
	}

	private void appendKidPreservingObject(PDStructureElement parent, Object kid) {
		if (kid instanceof PDStructureElement child) {
			child.setParent(parent);
			parent.appendKid(child);
		}
		else if (isDirectContentKid(kid)) {
			appendExistingKid(parent, kid);
		}
		else {
			List<Object> kids = new ArrayList<>(parent.getKids());
			kids.add(kid);
			parent.setKids(kids);
		}
	}

	private boolean ensureExistingLinkReferenceWrapped(PDStructureNode node, PDPage page, PDAnnotationLink annotation) {
		List<Object> kids = node.getKids();
		boolean changed = false;
		boolean found = false;
		for (int index = 0; index < kids.size(); index++) {
			Object kid = kids.get(index);
			if (kid instanceof PDObjectReference objectReference && referencesAnnotation(objectReference, annotation)
					&& node instanceof PDStructureElement parent) {
				found = true;
				if ("Link".equals(effectiveStructureType(parent))) {
					parent.setPage(page);
					objectReference.setPage(page);
				}
				else {
					PDStructureElement link = new PDStructureElement("Link", parent);
					link.setPage(page);
					String linkText = annotation.getContents();
					if (linkText != null && !linkText.isBlank()) {
						link.setAlternateDescription(linkText);
					}
					objectReference.setPage(page);
					link.appendKid(objectReference);
					kids.set(index, link);
					changed = true;
				}
			}
			else if (kid instanceof PDStructureElement element
					&& ensureExistingLinkReferenceWrapped(element, page, annotation)) {
				found = true;
				changed = true;
			}
		}
		if (changed) {
			node.setKids(kids);
		}
		return found;
	}

	private PDStructureElement annotationElement(PDStructureElement parent, PDPage page, PDObjectReference objectReference) {
		PDStructureElement annot = new PDStructureElement("Annot", parent);
		annot.setPage(page);
		objectReference.setPage(page);
		annot.appendKid(objectReference);
		return annot;
	}

	private PDStructureElement rootDocumentElement(PDStructureTreeRoot structureTreeRoot) {
		for (Object kid : structureTreeRoot.getKids()) {
			if (kid instanceof PDStructureElement element && "Document".equals(effectiveStructureType(element))) {
				return element;
			}
		}
		return null;
	}

	private String findEnclosingStructureAlt(PDDocument document, PDAnnotation annotation) {
		PDStructureTreeRoot structureTreeRoot = document.getDocumentCatalog().getStructureTreeRoot();
		if (structureTreeRoot == null) {
			return null;
		}
		return findEnclosingStructureAlt(structureTreeRoot, annotation);
	}

	private String findEnclosingStructureAlt(PDStructureNode node, PDAnnotation annotation) {
		for (Object kid : node.getKids()) {
			if (kid instanceof PDObjectReference objectReference && referencesAnnotation(objectReference, annotation)
					&& node instanceof PDStructureElement element) {
				return element.getAlternateDescription();
			}
			if (kid instanceof PDStructureElement element) {
				String nestedAlt = findEnclosingStructureAlt(element, annotation);
				if (nestedAlt != null) {
					return nestedAlt;
				}
			}
		}
		return null;
	}

	private boolean referencesAnnotation(PDObjectReference objectReference, PDAnnotation annotation) {
		COSObjectable referencedObject = objectReference.getReferencedObject();
		return referencedObject instanceof PDAnnotation referencedAnnotation
				&& referencedAnnotation.getCOSObject() == annotation.getCOSObject();
	}

	private void fixFontAndTextIssues(PDDocument document) throws IOException {
		for (PDPage page : document.getPages()) {
			PDResources resources = page.getResources();
			if (resources != null) {
				scanFonts(resources, new HashSet<>());
			}
		}
	}

	private void fixColorAndPdfSyntaxIssues(PDDocument document) throws IOException {
		PDDocumentCatalog catalog = document.getDocumentCatalog();
		if (catalog.getOutputIntents().isEmpty()) {
			ICC_Profile profile = ICC_Profile.getInstance(ColorSpace.CS_sRGB);
			try (ByteArrayInputStream input = new ByteArrayInputStream(profile.getData())) {
				PDOutputIntent outputIntent = new PDOutputIntent(document, input);
				outputIntent.setInfo("sRGB IEC61966-2.1");
				outputIntent.setOutputCondition("sRGB IEC61966-2.1");
				outputIntent.setOutputConditionIdentifier("sRGB IEC61966-2.1");
				outputIntent.setRegistryName("http://www.color.org");
				catalog.addOutputIntent(outputIntent);
			}
		}
	}

	private void ensureXmpMetadata(PDDocument document, String title, String language) throws IOException {
		PDMetadata existingMetadata = document.getDocumentCatalog().getMetadata();
		if (existingMetadata != null) {
			String existingXmp = new String(existingMetadata.exportXMPMetadata().readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8);
			String updatedXmp = addMissingXmpFields(existingXmp, title, language);
			if (!updatedXmp.equals(existingXmp)) {
				PDMetadata metadata = new PDMetadata(document);
				metadata.importXMPMetadata(updatedXmp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				document.getDocumentCatalog().setMetadata(metadata);
			}
			return;
		}

		String xmp = """
				<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
				<x:xmpmeta xmlns:x="adobe:ns:meta/">
				  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
				    <rdf:Description rdf:about=""
				      xmlns:dc="http://purl.org/dc/elements/1.1/"
				      xmlns:pdf="http://ns.adobe.com/pdf/1.3/"
				      xmlns:pdfuaid="http://www.aiim.org/pdfua/ns/id/">
				      <dc:title><rdf:Alt><rdf:li xml:lang="%s">%s</rdf:li></rdf:Alt></dc:title>
				      <dc:language><rdf:Bag><rdf:li>%s</rdf:li></rdf:Bag></dc:language>
				      <pdf:Producer>PDFBox</pdf:Producer>
				      <pdfuaid:part>2</pdfuaid:part>
				      <pdfuaid:rev>2024</pdfuaid:rev>
				    </rdf:Description>
				  </rdf:RDF>
				</x:xmpmeta>
				<?xpacket end="w"?>""".formatted(escapeXml(language), escapeXml(title), escapeXml(language));
		PDMetadata metadata = new PDMetadata(document);
		metadata.importXMPMetadata(xmp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		document.getDocumentCatalog().setMetadata(metadata);
	}

	private String addMissingXmpFields(String xmp, String title, String language) {
		String updated = xmp;
		updated = replaceXmpElementValue(updated, "pdfuaid:part", "2");
		updated = replaceXmpElementValue(updated, "pdfuaid:rev", "2024");
		if (!updated.contains("pdfuaid:part")) {
			updated = insertBefore(updated, "</rdf:RDF>", pdfuaDescription("part", "2"));
		}
		if (!updated.contains("pdfuaid:rev")) {
			updated = insertBefore(updated, "</rdf:RDF>", pdfuaDescription("rev", "2024"));
		}
		if (!updated.contains("<dc:title")) {
			updated = insertBefore(updated, "</rdf:RDF>",
					"    <rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
							+ "<dc:title><rdf:Alt><rdf:li xml:lang=\"" + escapeXml(language) + "\">"
							+ escapeXml(title) + "</rdf:li></rdf:Alt></dc:title></rdf:Description>\n");
		}
		if (!updated.contains("<dc:language")) {
			updated = insertBefore(updated, "</rdf:RDF>",
					"    <rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
							+ "<dc:language><rdf:Bag><rdf:li>" + escapeXml(language)
							+ "</rdf:li></rdf:Bag></dc:language></rdf:Description>\n");
		}
		return updated;
	}

	private String pdfuaDescription(String fieldName, String value) {
		return "    <rdf:Description rdf:about=\"\" xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\">"
				+ "<pdfuaid:" + fieldName + ">" + value + "</pdfuaid:" + fieldName + ">"
				+ "</rdf:Description>\n";
	}

	private String replaceXmpElementValue(String xmp, String elementName, String value) {
		String open = "<" + elementName + ">";
		String close = "</" + elementName + ">";
		int start = xmp.indexOf(open);
		int end = xmp.indexOf(close);
		if (start < 0 || end < start) {
			return xmp;
		}
		return xmp.substring(0, start + open.length()) + value + xmp.substring(end);
	}

	private String insertBefore(String value, String marker, String insertion) {
		int index = value.indexOf(marker);
		if (index < 0) {
			return value;
		}
		return value.substring(0, index) + insertion + value.substring(index);
	}

	private void ensureRoleMap(PDStructureTreeRoot structureTreeRoot) {
		Map<String, String> roleMap = new HashMap<>();
		Map<String, Object> existing = structureTreeRoot.getRoleMap();
		if (existing != null) {
			for (Map.Entry<String, Object> entry : existing.entrySet()) {
				String key = entry.getKey();
				String value = roleMapValue(entry.getValue());
				if (!key.equals(value) && STANDARD_STRUCTURE_TYPES.contains(value)
						&& !"Span".equals(value)) {
					roleMap.put(key, "Document".equals(value) ? "Part" : value);
				}
			}
		}

		roleMap.putIfAbsent("Heading", "H");
		roleMap.putIfAbsent("Title", "H");
		roleMap.putIfAbsent("Body", "Sect");
		roleMap.putIfAbsent("Paragraph", "P");
		roleMap.putIfAbsent("Image", "Figure");
		roleMap.putIfAbsent("List", "L");
		roleMap.putIfAbsent("ListItem", "LI");
		roleMap.putIfAbsent("TableRow", "TR");
		roleMap.putIfAbsent("TableHeaderCell", "TH");
		roleMap.putIfAbsent("TableCell", "TD");
		structureTreeRoot.setRoleMap(roleMap);
	}

	private String roleMapValue(Object value) {
		if (value instanceof COSName name) {
			return name.getName();
		}
		return String.valueOf(value);
	}

	private void ensureSingleDocumentRoot(PDStructureTreeRoot structureTreeRoot) {
		List<Object> rootKids = structureTreeRoot.getKids();
		PDStructureElement documentElement = null;
		List<Object> documentKids = new ArrayList<>();

		for (Object kid : rootKids) {
			if (kid instanceof PDStructureElement element && "Document".equals(effectiveStructureType(element))) {
				if (documentElement == null) {
					documentElement = element;
				}
				documentKids.addAll(element.getKids());
			}
			else {
				documentKids.add(kid);
			}
		}

		if (documentElement == null) {
			documentElement = new PDStructureElement("Document", structureTreeRoot);
		}
		documentElement.setParent(structureTreeRoot);
		ensurePdf20Namespace(documentElement);
		documentElement.setKids(documentKids);
		for (Object kid : documentKids) {
			if (kid instanceof PDStructureElement element) {
				element.setParent(documentElement);
			}
		}
		structureTreeRoot.setKids(List.of(documentElement));
	}

	private void normalizeStructureNode(PDStructureNode node, PDStructureNode parent) {
		List<Object> normalizedKids = new ArrayList<>();
		for (Object kid : node.getKids()) {
			if (kid instanceof PDStructureElement element) {
				if (parent != null && element.getParent() != parent) {
					element.setParent(parent);
				}
				normalizeStructureNode(element, element);
				normalizedKids.add(normalizedStructureKid(node, element));
			}
			else if (node instanceof PDStructureElement element
					&& ("Document".equals(effectiveStructureType(element)) || "Sect".equals(effectiveStructureType(element)))
					&& isStructureContentItem(kid)) {
				normalizedKids.add(wrapStructureContentItem(element, "P", kid));
			}
			else if (isDirectContentKid(kid) && node instanceof PDStructureElement element
					&& "TBody".equals(effectiveStructureType(element))) {
				normalizedKids.add(tableCellForDirectContent(new PDStructureElement("TR", element), kid));
			}
			else if (isDirectContentKid(kid) && node instanceof PDStructureElement element
					&& "TR".equals(effectiveStructureType(element))) {
				PDStructureElement tableCell = new PDStructureElement("TD", element);
				appendExistingKid(tableCell, kid);
				normalizedKids.add(tableCell);
			}
			else {
				normalizedKids.add(kid);
			}
		}
		node.setKids(normalizedKids);
	}

	private Object normalizedStructureKid(PDStructureNode parentNode, PDStructureElement child) {
		if (parentNode instanceof PDStructureElement parent) {
			String parentType = effectiveStructureType(parent);
			String childType = effectiveStructureType(child);
			if (isRootGroupingType(parentType) && "Span".equals(childType)) {
				child.setStructureType("P");
				return child;
			}
			if ("TBody".equals(parentType) && !Set.of("TR").contains(childType)) {
				PDStructureElement row = new PDStructureElement("TR", parent);
				PDStructureElement cell = new PDStructureElement("TD", row);
				child.setParent(cell);
				cell.appendKid(child);
				row.appendKid(cell);
				return row;
			}
			if ("TR".equals(parentType) && !Set.of("TH", "TD").contains(childType)) {
				PDStructureElement cell = new PDStructureElement("TD", parent);
				child.setParent(cell);
				cell.appendKid(child);
				return cell;
			}
		}
		return child;
	}

	private PDStructureElement wrapStructureContentItem(PDStructureElement parent, String wrapperType, Object contentItem) {
		PDStructureElement wrapper = new PDStructureElement(wrapperType, parent);
		if (parent.getPage() != null) {
			wrapper.setPage(parent.getPage());
		}
		if (isDirectContentKid(contentItem)) {
			appendExistingKid(wrapper, contentItem);
		}
		else {
			wrapper.setKids(List.of(contentItem));
		}
		return wrapper;
	}

	private void removeDirectSpanChildren(PDStructureNode node) {
		for (Object kid : node.getKids()) {
			if (kid instanceof PDStructureElement element) {
				if (node instanceof PDStructureElement parent
						&& isRootGroupingType(effectiveStructureType(parent))
						&& "Span".equals(effectiveStructureType(element))) {
					element.setStructureType("P");
				}
				removeDirectSpanChildren(element);
			}
		}
	}

	private void removeDirectContentItemsFromDocument(PDStructureTreeRoot structureTreeRoot) {
		for (Object kid : structureTreeRoot.getKids()) {
			if (kid instanceof PDStructureElement element && "Document".equals(effectiveStructureType(element))) {
				removeDirectContentItemsFromStructureElement(element);
			}
		}
	}

	private void removeDirectContentItemsFromStructureElement(PDStructureElement element) {
		if (!"Document".equals(effectiveStructureType(element)) && !"Sect".equals(effectiveStructureType(element))) {
			return;
		}

		COSDictionary elementDictionary = element.getCOSObject();
		COSBase kids = elementDictionary.getDictionaryObject(COSName.K);
		if (kids == null) {
			return;
		}

		if (kids instanceof COSArray kidsArray) {
			COSArray normalizedKids = new COSArray();
			for (int index = 0; index < kidsArray.size(); index++) {
				COSBase kid = kidsArray.get(index);
				normalizedKids.add(normalizedStructureKid(elementDictionary, kid));
			}
			elementDictionary.setItem(COSName.K, normalizedKids);
		}
		else {
			elementDictionary.setItem(COSName.K, normalizedStructureKid(elementDictionary, kids));
		}
	}

	private COSBase normalizedStructureKid(COSDictionary parentDictionary, COSBase kid) {
		if (isStructureElementDictionary(kid)) {
			COSDictionary kidDictionary = asDictionary(kid);
			if (kidDictionary != null) {
				kidDictionary.setItem(COSName.P, parentDictionary);
			}
			return kid;
		}
		return contentItemWrapper(parentDictionary, kid);
	}

	private COSDictionary contentItemWrapper(COSDictionary parentDictionary, COSBase contentItem) {
		COSDictionary wrapper = new COSDictionary();
		wrapper.setName(COSName.TYPE, COSName.STRUCT_ELEM.getName());
		wrapper.setName(COSName.S, "P");
		wrapper.setItem(COSName.P, parentDictionary);
		COSBase page = parentDictionary.getDictionaryObject(COSName.PG);
		if (page != null) {
			wrapper.setItem(COSName.PG, page);
		}
		wrapper.setItem(COSName.K, contentItem);
		return wrapper;
	}

	private boolean isStructureElementDictionary(COSBase value) {
		COSDictionary dictionary = asDictionary(value);
		return dictionary != null && COSName.STRUCT_ELEM.equals(dictionary.getCOSName(COSName.TYPE));
	}

	private COSDictionary asDictionary(COSBase value) {
		COSBase unwrapped = value instanceof COSObject object ? object.getObject() : value;
		return unwrapped instanceof COSDictionary dictionary ? dictionary : null;
	}

	private String effectiveStructureType(PDStructureElement element) {
		String standardType = element.getStandardStructureType();
		return standardType == null || standardType.isBlank() ? element.getStructureType() : standardType;
	}

	private boolean isRootGroupingType(String structureType) {
		return "Document".equals(structureType) || "Part".equals(structureType) || "Sect".equals(structureType);
	}

	private PDStructureElement tableCellForDirectContent(PDStructureElement row, Object contentItem) {
		PDStructureElement tableCell = new PDStructureElement("TD", row);
		appendExistingKid(tableCell, contentItem);
		row.appendKid(tableCell);
		return row;
	}

	private void assignMissingStructParents(PDDocument document, PDStructureTreeRoot structureTreeRoot) {
		int nextKey = Math.max(0, structureTreeRoot.getParentTreeNextKey());
		for (PDPage page : document.getPages()) {
			if (page.getStructParents() < 0) {
				page.setStructParents(nextKey++);
			}
		}
		structureTreeRoot.setParentTreeNextKey(nextKey);
	}

	private void setMissingPageEntriesForMcidElements(PDDocument document, PDStructureTreeRoot structureTreeRoot) {
		PDPage firstPage = document.getNumberOfPages() == 0 ? null : document.getPage(0);
		for (Object kid : structureTreeRoot.getKids()) {
			if (kid instanceof PDStructureElement element) {
				setMissingPageEntriesForMcidElements(element, null, firstPage);
			}
		}
	}

	private PDPage setMissingPageEntriesForMcidElements(PDStructureElement element, PDPage inheritedPage, PDPage fallbackPage) {
		PDPage currentPage = element.getPage() != null ? element.getPage() : inheritedPage;
		PDPage discoveredPage = currentPage;
		boolean containsDirectMcid = false;

		for (Object kid : element.getKids()) {
			if (kid instanceof PDStructureElement childElement) {
				PDPage childPage = setMissingPageEntriesForMcidElements(childElement, currentPage, fallbackPage);
				if (discoveredPage == null) {
					discoveredPage = childPage;
				}
			}
			else if (kid instanceof PDMarkedContentReference markedContentReference) {
				containsDirectMcid = true;
				if (markedContentReference.getPage() != null) {
					discoveredPage = markedContentReference.getPage();
				}
			}
			else if (kid instanceof PDObjectReference objectReference && objectReference.getPage() != null) {
				discoveredPage = objectReference.getPage();
			}
			else if (kid instanceof Number || kid instanceof COSInteger) {
				containsDirectMcid = true;
			}
		}

		if (containsDirectMcid) {
			PDPage page = discoveredPage != null ? discoveredPage : fallbackPage;
			if (page != null && element.getPage() == null) {
				element.setPage(page);
			}
			for (Object kid : element.getKids()) {
				if (kid instanceof PDMarkedContentReference markedContentReference && markedContentReference.getPage() == null) {
					markedContentReference.setPage(page);
				}
			}
			return page;
		}
		return discoveredPage;
	}

	private int nextStructParentKey(PDDocument document) {
		PDStructureTreeRoot structureTreeRoot = document.getDocumentCatalog().getStructureTreeRoot();
		return structureTreeRoot == null ? 0 : Math.max(0, structureTreeRoot.getParentTreeNextKey());
	}

	private void rebuildStructuralParentTree(PDDocument document) throws IOException {
		PDStructureTreeRoot structureTreeRoot = document.getDocumentCatalog().getStructureTreeRoot();
		if (structureTreeRoot == null) {
			return;
		}

		Map<PDPage, Integer> pageKeys = pageStructParentKeys(document);
		Map<Integer, COSBase> parentTreeEntries = new HashMap<>();
		for (Object kid : structureTreeRoot.getKids()) {
			if (kid instanceof PDStructureElement element) {
				collectParentTreeEntries(element, null, pageKeys, parentTreeEntries);
			}
		}

		COSDictionary parentTree = new COSDictionary();
		COSArray nums = new COSArray();
		parentTreeEntries.keySet().stream().sorted().forEach(key -> {
			nums.add(COSInteger.get(key));
			nums.add(parentTreeEntries.get(key));
		});
		parentTree.setItem(COSName.NUMS, nums);
		structureTreeRoot.setParentTree(new PDNumberTreeNode(parentTree, COSObjectable.class));
		int nextKey = parentTreeEntries.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
		structureTreeRoot.setParentTreeNextKey(nextKey);
	}

	private Map<PDPage, Integer> pageStructParentKeys(PDDocument document) {
		Map<PDPage, Integer> pageKeys = new HashMap<>();
		int nextKey = 0;
		for (PDPage page : document.getPages()) {
			int key = page.getStructParents();
			if (key < 0) {
				while (pageKeys.containsValue(nextKey)) {
					nextKey++;
				}
				key = nextKey++;
				page.setStructParents(key);
			}
			pageKeys.put(page, key);
			nextKey = Math.max(nextKey, key + 1);
		}
		return pageKeys;
	}

	private PDPage collectParentTreeEntries(
			PDStructureElement element,
			PDPage inheritedPage,
			Map<PDPage, Integer> pageKeys,
			Map<Integer, COSBase> parentTreeEntries) {

		PDPage currentPage = element.getPage() != null ? element.getPage() : inheritedPage;
		PDPage discoveredPage = currentPage;
		for (Object kid : element.getKids()) {
			if (kid instanceof PDStructureElement childElement) {
				PDPage childPage = collectParentTreeEntries(childElement, currentPage, pageKeys, parentTreeEntries);
				if (discoveredPage == null) {
					discoveredPage = childPage;
				}
			}
			else if (kid instanceof PDMarkedContentReference markedContentReference) {
				PDPage contentPage = markedContentReference.getPage() != null ? markedContentReference.getPage() : currentPage;
				addMcidParentTreeEntry(contentPage, markedContentReference.getMCID(), element, pageKeys, parentTreeEntries);
				discoveredPage = contentPage != null ? contentPage : discoveredPage;
			}
			else if (kid instanceof PDObjectReference objectReference) {
				addObjectReferenceParentTreeEntry(objectReference, element, parentTreeEntries);
				if (objectReference.getPage() != null) {
					discoveredPage = objectReference.getPage();
				}
			}
			else if (kid instanceof Number number) {
				addMcidParentTreeEntry(currentPage, number.intValue(), element, pageKeys, parentTreeEntries);
			}
			else if (kid instanceof COSInteger integer) {
				addMcidParentTreeEntry(currentPage, integer.intValue(), element, pageKeys, parentTreeEntries);
			}
		}
		return discoveredPage;
	}

	private void addMcidParentTreeEntry(
			PDPage page,
			int mcid,
			PDStructureElement element,
			Map<PDPage, Integer> pageKeys,
			Map<Integer, COSBase> parentTreeEntries) {
		if (page == null || mcid < 0) {
			return;
		}
		Integer pageKey = pageKeys.get(page);
		if (pageKey == null) {
			return;
		}
		COSArray parents = parentTreeEntries.get(pageKey) instanceof COSArray existing ? existing : new COSArray();
		parents.growToSize(mcid + 1, COSNull.NULL);
		parents.set(mcid, element.getCOSObject());
		parentTreeEntries.put(pageKey, parents);
	}

	private void addObjectReferenceParentTreeEntry(
			PDObjectReference objectReference,
			PDStructureElement element,
			Map<Integer, COSBase> parentTreeEntries) {
		COSObjectable referencedObject = objectReference.getReferencedObject();
		if (referencedObject instanceof PDAnnotation annotation && annotation.getStructParent() >= 0) {
			parentTreeEntries.put(annotation.getStructParent(), element.getCOSObject());
		}
	}

	private void normalizeTableStructure(PDStructureNode node) {
		for (Object kid : node.getKids()) {
			if (kid instanceof PDStructureElement element) {
				String type = element.getStructureType();
				if ("TBody".equals(type)) {
					for (Object child : element.getKids()) {
						if (child instanceof PDStructureElement childElement && "Span".equals(effectiveStructureType(childElement))) {
							childElement.setStructureType("TR");
						}
					}
				}
				if ("TH".equals(type)) {
					COSDictionary dictionary = element.getCOSObject();
					if (!dictionary.containsKey(COSName.getPDFName("Scope"))) {
						dictionary.setName(COSName.getPDFName("Scope"), "Column");
					}
				}
				if ("TD".equals(type) || "TH".equals(type)) {
					normalizePositiveIntegerAttribute(element, "RowSpan");
					normalizePositiveIntegerAttribute(element, "ColSpan");
				}
				normalizeTableStructure(element);
				if ("Table".equals(type) || "TBody".equals(type) || "THead".equals(type) || "TFoot".equals(type)) {
					regularizeRows(element);
				}
			}
		}
	}

	private void regularizeRows(PDStructureElement tableContainer) {
		List<PDStructureElement> rows = directChildrenOfType(tableContainer, "TR");
		if (rows.isEmpty()) {
			return;
		}
		int maxColumns = 0;
		for (PDStructureElement row : rows) {
			maxColumns = Math.max(maxColumns, tableColumnCount(row));
		}
		for (PDStructureElement row : rows) {
			while (tableColumnCount(row) < maxColumns) {
				PDStructureElement cell = new PDStructureElement("TD", row);
				PDStructureElement paragraph = new PDStructureElement("P", cell);
				cell.appendKid(paragraph);
				row.appendKid(cell);
			}
		}
	}

	private List<PDStructureElement> directChildrenOfType(PDStructureElement parent, String structureType) {
		List<PDStructureElement> children = new ArrayList<>();
		for (Object kid : parent.getKids()) {
			if (kid instanceof PDStructureElement element && structureType.equals(element.getStructureType())) {
				children.add(element);
			}
		}
		return children;
	}

	private int tableColumnCount(PDStructureElement row) {
		int count = 0;
		for (Object kid : row.getKids()) {
			if (kid instanceof PDStructureElement element
					&& ("TD".equals(element.getStructureType()) || "TH".equals(element.getStructureType()))) {
				count += Math.max(1, element.getCOSObject().getInt(COSName.getPDFName("ColSpan"), 1));
			}
		}
		return count;
	}

	private void normalizeListStructure(PDStructureNode node) {
		for (Object kid : node.getKids()) {
			if (kid instanceof PDStructureElement element) {
				if ("LI".equals(element.getStructureType())) {
					ensureListItemParts(element);
				}
				normalizeListStructure(element);
			}
		}
	}

	private void normalizeHeadingStructure(PDStructureNode node) {
		int previousHeadingLevel = 0;
		for (PDStructureElement element : flattenStructureElements(node)) {
			int level = headingLevel(element.getStructureType());
			if (level > 0) {
				if (previousHeadingLevel > 0 && level > previousHeadingLevel + 1) {
					element.setStructureType("H" + (previousHeadingLevel + 1));
					level = previousHeadingLevel + 1;
				}
				previousHeadingLevel = level;
			}
		}
	}

	private void ensureHeadingBookmarks(PDDocument document, PDStructureTreeRoot structureTreeRoot) {
		if (document.getDocumentCatalog().getDocumentOutline() != null) {
			return;
		}

		List<PDStructureElement> headings = flattenStructureElements(structureTreeRoot).stream()
				.filter(element -> headingLevel(effectiveStructureType(element)) > 0)
				.toList();
		if (headings.isEmpty()) {
			return;
		}

		PDDocumentOutline outline = new PDDocumentOutline();
		for (PDStructureElement heading : headings) {
			PDPage page = heading.getPage();
			if (page == null && document.getNumberOfPages() > 0) {
				page = document.getPage(0);
			}
			if (page == null) {
				continue;
			}

			PDOutlineItem item = new PDOutlineItem();
			item.setTitle(headingBookmarkTitle(heading));
			item.setStructureElement(heading);
			item.getCOSObject().setItem(COSName.DEST, structureDestination(heading));
			outline.addLast(item);
		}
		if (outline.getFirstChild() != null) {
			outline.openNode();
			document.getDocumentCatalog().setDocumentOutline(outline);
		}
	}

	private String headingBookmarkTitle(PDStructureElement heading) {
		if (heading.getTitle() != null && !heading.getTitle().isBlank()) {
			return heading.getTitle();
		}
		if (heading.getActualText() != null && !heading.getActualText().isBlank()) {
			return heading.getActualText();
		}
		if (heading.getAlternateDescription() != null && !heading.getAlternateDescription().isBlank()) {
			return heading.getAlternateDescription();
		}
		return effectiveStructureType(heading);
	}

	private COSArray structureDestination(PDStructureElement element) {
		COSArray destination = new COSArray();
		destination.add(element.getCOSObject());
		destination.add(COSName.getPDFName("Fit"));
		return destination;
	}

	private void repairFigureStructure(PDDocument document, PDStructureNode node) {
		for (Object kid : node.getKids()) {
			if (kid instanceof PDStructureElement element) {
				if ("Figure".equals(effectiveStructureType(element))) {
					repairFigureElement(document, element);
				}
				repairFigureStructure(document, element);
			}
		}
	}

	private void repairFigureElement(PDDocument document, PDStructureElement element) {
		if (element.getAlternateDescription() == null || element.getAlternateDescription().isBlank()) {
			element.setAlternateDescription("Figure");
		}
		ensureFigureBoundingBox(document, element);
	}

	private void ensureFigureBoundingBox(PDDocument document, PDStructureElement element) {
		COSDictionary figureDictionary = element.getCOSObject();
		if (hasLayoutBoundingBox(figureDictionary.getDictionaryObject(COSName.getPDFName("A")))) {
			return;
		}

		PDPage page = element.getPage();
		if (page == null && document.getNumberOfPages() > 0) {
			page = document.getPage(0);
			element.setPage(page);
		}
		if (page == null) {
			return;
		}

		COSDictionary layoutAttributes = new COSDictionary();
		layoutAttributes.setName(COSName.getPDFName("O"), "Layout");
		layoutAttributes.setItem(COSName.getPDFName("BBox"), boundingBoxArray(page.getCropBox()));

		COSBase attributes = figureDictionary.getDictionaryObject(COSName.getPDFName("A"));
		if (attributes instanceof COSArray attributeArray) {
			attributeArray.add(layoutAttributes);
		}
		else if (attributes != null) {
			COSArray attributeArray = new COSArray();
			attributeArray.add(attributes);
			attributeArray.add(layoutAttributes);
			figureDictionary.setItem(COSName.getPDFName("A"), attributeArray);
		}
		else {
			figureDictionary.setItem(COSName.getPDFName("A"), layoutAttributes);
		}
	}

	private boolean hasLayoutBoundingBox(COSBase attributes) {
		if (attributes instanceof COSArray attributeArray) {
			for (int index = 0; index < attributeArray.size(); index++) {
				if (hasLayoutBoundingBox(attributeArray.getObject(index))) {
					return true;
				}
			}
			return false;
		}
		COSDictionary dictionary = asDictionary(attributes);
		return dictionary != null
				&& "Layout".equals(dictionary.getNameAsString(COSName.getPDFName("O")))
				&& dictionary.getDictionaryObject(COSName.getPDFName("BBox")) instanceof COSArray;
	}

	private COSArray boundingBoxArray(PDRectangle rectangle) {
		COSArray bbox = new COSArray();
		bbox.setFloatArray(new float[] {
				rectangle.getLowerLeftX(),
				rectangle.getLowerLeftY(),
				rectangle.getUpperRightX(),
				rectangle.getUpperRightY()
		});
		return bbox;
	}

	private void scanFonts(PDResources resources, Set<COSBase> visitedResources) throws IOException {
		if (!visitedResources.add(resources.getCOSObject())) {
			return;
		}
		for (COSName fontName : resources.getFontNames()) {
			PDFont font = resources.getFont(fontName);
			if (font != null) {
				COSDictionary fontDictionary = font.getCOSObject();
				fontDictionary.getDictionaryObject(COSName.TO_UNICODE);
			}
		}
		scanNestedFormResources(resources, visitedResources);
	}

	private void scanImages(PDResources resources, Set<COSBase> visitedResources) throws IOException {
		if (!visitedResources.add(resources.getCOSObject())) {
			return;
		}
		for (COSName name : resources.getXObjectNames()) {
			PDXObject xObject = resources.getXObject(name);
			if (xObject instanceof PDImageXObject image) {
				image.getCOSObject().setName(COSName.getPDFName("Intent"), "Perceptual");
			}
			else if (xObject instanceof PDFormXObject form && form.getResources() != null) {
				scanImages(form.getResources(), visitedResources);
			}
		}
	}

	private void scanXObjectsForArtifacts(PDResources resources, Set<COSBase> visitedResources) throws IOException {
		if (!visitedResources.add(resources.getCOSObject())) {
			return;
		}
		for (COSName name : resources.getXObjectNames()) {
			PDXObject xObject = resources.getXObject(name);
			if (xObject instanceof PDFormXObject form && form.getResources() != null) {
				scanXObjectsForArtifacts(form.getResources(), visitedResources);
			}
		}
	}

	private void scanNestedFormResources(PDResources resources, Set<COSBase> visitedResources) throws IOException {
		for (COSName name : resources.getXObjectNames()) {
			PDXObject xObject = resources.getXObject(name);
			if (xObject instanceof PDFormXObject form && form.getResources() != null) {
				scanFonts(form.getResources(), visitedResources);
			}
		}
	}

	private List<PDStructureElement> flattenStructureElements(PDStructureNode node) {
		List<PDStructureElement> elements = new ArrayList<>();
		for (Object kid : node.getKids()) {
			if (kid instanceof PDStructureElement element) {
				elements.add(element);
				elements.addAll(flattenStructureElements(element));
			}
		}
		return elements;
	}

	private void ensureListItemParts(PDStructureElement listItem) {
		boolean hasLabel = false;
		boolean hasBody = false;
		for (Object kid : listItem.getKids()) {
			if (kid instanceof PDStructureElement element) {
				hasLabel = hasLabel || "Lbl".equals(element.getStructureType());
				hasBody = hasBody || "LBody".equals(element.getStructureType());
			}
		}
		if (!hasLabel) {
			listItem.getKids().add(0, new PDStructureElement("Lbl", listItem));
		}
		if (!hasBody) {
			listItem.appendKid(new PDStructureElement("LBody", listItem));
		}
	}

	private void normalizePositiveIntegerAttribute(PDStructureElement element, String key) {
		COSBase value = element.getCOSObject().getDictionaryObject(COSName.getPDFName(key));
		if (value instanceof COSInteger integer && integer.intValue() < 1) {
			element.getCOSObject().setInt(COSName.getPDFName(key), 1);
		}
	}

	private boolean isDirectContentKid(Object kid) {
		return kid instanceof Number || kid instanceof COSInteger
				|| kid instanceof PDMarkedContentReference || kid instanceof PDObjectReference;
	}

	private boolean isStructureContentItem(Object kid) {
		return !(kid instanceof PDStructureElement);
	}

	private void appendExistingKid(PDStructureElement parent, Object kid) {
		if (kid instanceof Number number) {
			parent.appendKid(number.intValue());
		}
		else if (kid instanceof COSInteger integer) {
			parent.appendKid(integer.intValue());
		}
		else if (kid instanceof PDMarkedContentReference markedContentReference) {
			parent.appendKid(markedContentReference);
		}
		else if (kid instanceof PDObjectReference objectReference) {
			parent.appendKid(objectReference);
		}
	}

	private void ensurePdf20Namespace(PDStructureElement documentElement) {
		COSBase namespace = documentElement.getCOSObject().getDictionaryObject(COSName.getPDFName("NS"));
		if (namespace instanceof COSDictionary namespaceDictionary
				&& "http://iso.org/pdf2/ssn".equals(namespaceDictionary.getString(COSName.getPDFName("NS")))) {
			return;
		}
		COSDictionary namespaceDictionary = new COSDictionary();
		namespaceDictionary.setName(COSName.TYPE, "Namespace");
		namespaceDictionary.setItem(COSName.getPDFName("NS"), new COSString("http://iso.org/pdf2/ssn"));
		documentElement.getCOSObject().setItem(COSName.getPDFName("NS"), namespaceDictionary);
	}

	private int headingLevel(String structureType) {
		if (structureType != null && structureType.length() == 2 && structureType.charAt(0) == 'H'
				&& Character.isDigit(structureType.charAt(1))) {
			return Character.digit(structureType.charAt(1), 10);
		}
		return 0;
	}

	private String escapeXml(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&apos;");
	}

	private static final Set<String> STANDARD_STRUCTURE_TYPES = Set.of(
			"Document", "Part", "Art", "Sect", "Div", "BlockQuote", "Caption", "TOC", "TOCI", "Index",
			"NonStruct", "Private", "P", "H", "H1", "H2", "H3", "H4", "H5", "H6", "L", "LI", "Lbl", "LBody",
			"Table", "TR", "TH", "TD", "THead", "TBody", "TFoot", "Span", "Quote", "Note", "Reference",
			"BibEntry", "Code", "Link", "Annot", "Ruby", "RB", "RT", "RP", "Warichu", "WT", "WP", "Figure",
			"Formula", "Form");

	private String runVeraPdf(Path filePath) {
		ProcessBuilder processBuilder = new ProcessBuilder(
				properties.getVeraPdfCommand(),
				"--format",
				"json",
				"--flavour",
				properties.getVeraPdfProfile(),
				"--passed",
				filePath.toString());
		processBuilder.redirectErrorStream(true);

		try {
			Process process = processBuilder.start();
			//boolean completed = process.waitFor(VALIDATION_TIMEOUT.toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
			String output = new String(process.getInputStream().readAllBytes());
			/*if (!completed) {
				process.destroyForcibly();
				throw new PdfValidationException("veraPDF validation timed out.");
			}
			if (process.exitValue() != 0 && output.isBlank()) {
				throw new PdfValidationException("veraPDF validation failed with exit code " + process.exitValue() + ".");
			}*/
			return output;
		}
		catch (IOException ex) {
			throw new PdfValidationException("Unable to run veraPDF command. Configure pdf.validation.vera-pdf-command.", ex);
		}
    }

	private JsonNode parseReport(String report) {
		try {
			return readFirstJsonValue(report);
		}
		catch (Exception ex) {
			throw new PdfValidationException("Unable to parse veraPDF JSON report.", ex);
		}
	}

	private JsonNode readFirstJsonValue(String report) throws IOException {
		if (report == null || report.isBlank()) {
			throw new PdfValidationException("veraPDF returned an empty report.");
		}

		for (int start = 0; start < report.length(); start++) {
			char current = report.charAt(start);
			if (current != '{' && current != '[') {
				continue;
			}
			int end = matchingJsonEnd(report, start);
			if (end < 0) {
				continue;
			}
			String candidate = report.substring(start, end + 1);
			try {
				return objectMapper.readTree(candidate);
			}
			catch (Exception ignored) {
				// Log lines can contain bracketed values like [main] before the JSON report.
			}
		}
		throw new PdfValidationException("veraPDF output did not contain a valid JSON report: " + preview(report));
	}

	private int matchingJsonEnd(String value, int start) {
		char opening = value.charAt(start);
		char closing = opening == '{' ? '}' : ']';
		int depth = 0;
		boolean inString = false;
		boolean escaped = false;

		for (int index = start; index < value.length(); index++) {
			char current = value.charAt(index);
			if (inString) {
				if (escaped) {
					escaped = false;
				}
				else if (current == '\\') {
					escaped = true;
				}
				else if (current == '"') {
					inString = false;
				}
				continue;
			}

			if (current == '"') {
				inString = true;
			}
			else if (current == opening) {
				depth++;
			}
			else if (current == closing) {
				depth--;
				if (depth == 0) {
					return index;
				}
			}
		}
		return -1;
	}

	private String preview(String value) {
		String singleLine = value.replaceAll("\\s+", " ").trim();
		return singleLine.length() <= 300 ? singleLine : singleLine.substring(0, 300) + "...";
	}

	private RuleSummary parseRules(JsonNode report) {
		List<PdfRuleResult> passedRules = new ArrayList<>();
		List<PdfRuleResult> failedRules = new ArrayList<>();
		collectRuleResults(report, passedRules, failedRules);
		return new RuleSummary(passedRules, failedRules);
	}

	private void collectRuleResults(JsonNode node, List<PdfRuleResult> passedRules, List<PdfRuleResult> failedRules) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			if (isRuleResultNode(node)) {
				PdfRuleResult result = toRuleResult(node);
				if ("passed".equalsIgnoreCase(result.status())) {
					passedRules.add(result);
				}
				else if ("failed".equalsIgnoreCase(result.status())) {
					failedRules.add(result);
				}
			}
			for (JsonNode child : node.values()) {
				collectRuleResults(child, passedRules, failedRules);
			}
		}
		else if (node.isArray()) {
			for (JsonNode child : node) {
				collectRuleResults(child, passedRules, failedRules);
			}
		}
	}

	private boolean isRuleResultNode(JsonNode node) {
		return !text(node, "status").isBlank()
				&& (node.has("ruleId") || node.has("specification") || node.has("clause") || node.has("testNumber"));
	}

	private PdfRuleResult toRuleResult(JsonNode ruleNode) {
		JsonNode ruleId = ruleNode.path("ruleId");
		String specification = firstText(ruleId, ruleNode, "specification");
		String clause = firstText(ruleId, ruleNode, "clause");
		String testNumber = firstText(ruleId, ruleNode, "testNumber");
		String status = text(ruleNode, "status");
		String mapping = clause.isBlank() && testNumber.isBlank() ? "" : clause + ":" + testNumber;
		return new PdfRuleResult(specification, clause, testNumber, status, mapping, text(ruleNode, "description"));
	}

	private String firstText(JsonNode preferredNode, JsonNode fallbackNode, String fieldName) {
		String preferredValue = text(preferredNode, fieldName);
		return preferredValue.isBlank() ? text(fallbackNode, fieldName) : preferredValue;
	}

	private String text(JsonNode node, String fieldName) {
		return node == null ? "" : node.path(fieldName).asText("");
	}

	private void deleteRecursively(Path path) {
		if (path == null || !Files.exists(path)) {
			return;
		}
		try (var stream = Files.walk(path)) {
			stream.sorted(java.util.Comparator.reverseOrder()).forEach(file -> {
				try {
					Files.deleteIfExists(file);
				}
				catch (IOException ignored) {
				}
			});
		}
		catch (IOException ignored) {
		}
	}

	private record RuleSummary(List<PdfRuleResult> passedRules, List<PdfRuleResult> failedRules) {
	}
}
