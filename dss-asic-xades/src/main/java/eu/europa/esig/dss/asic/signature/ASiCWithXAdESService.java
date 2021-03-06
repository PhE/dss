package eu.europa.esig.dss.asic.signature;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import eu.europa.esig.dss.DSSDocument;
import eu.europa.esig.dss.DSSException;
import eu.europa.esig.dss.DSSUtils;
import eu.europa.esig.dss.DomUtils;
import eu.europa.esig.dss.SignaturePackaging;
import eu.europa.esig.dss.SignatureValue;
import eu.europa.esig.dss.SigningOperation;
import eu.europa.esig.dss.ToBeSigned;
import eu.europa.esig.dss.asic.ASiCNamespace;
import eu.europa.esig.dss.asic.ASiCParameters;
import eu.europa.esig.dss.asic.ASiCUtils;
import eu.europa.esig.dss.asic.ASiCWithXAdESContainerExtractor;
import eu.europa.esig.dss.asic.ASiCWithXAdESSignatureParameters;
import eu.europa.esig.dss.asic.AbstractASiCContainerExtractor;
import eu.europa.esig.dss.asic.ManifestNamespace;
import eu.europa.esig.dss.utils.Utils;
import eu.europa.esig.dss.validation.CertificateVerifier;
import eu.europa.esig.dss.validation.TimestampToken;
import eu.europa.esig.dss.xades.XAdESSignatureParameters;
import eu.europa.esig.dss.xades.signature.XAdESService;

@SuppressWarnings("serial")
public class ASiCWithXAdESService extends AbstractASiCSignatureService<ASiCWithXAdESSignatureParameters> {

	private static final Logger LOG = LoggerFactory.getLogger(ASiCWithXAdESService.class);

	static {
		DomUtils.registerNamespace("asic", ASiCNamespace.NS);
		DomUtils.registerNamespace("manifest", ManifestNamespace.NS);
	}

	public ASiCWithXAdESService(CertificateVerifier certificateVerifier) {
		super(certificateVerifier);
		LOG.debug("+ ASiCService with XAdES created");
	}

	@Override
	public TimestampToken getContentTimestamp(List<DSSDocument> toSignDocuments, ASiCWithXAdESSignatureParameters parameters) {
		GetDataToSignASiCWithXAdESHelper getDataToSignHelper = ASiCWithXAdESDataToSignHelperBuilder.getGetDataToSignHelper(toSignDocuments, parameters);
		return getXAdESService().getContentTimestamp(getDataToSignHelper.getSignedDocuments(), parameters);
	}

	@Override
	public ToBeSigned getDataToSign(List<DSSDocument> toSignDocuments, ASiCWithXAdESSignatureParameters parameters) throws DSSException {
		GetDataToSignASiCWithXAdESHelper dataToSignHelper = ASiCWithXAdESDataToSignHelperBuilder.getGetDataToSignHelper(toSignDocuments, parameters);
		XAdESSignatureParameters xadesParameters = getXAdESParameters(parameters, dataToSignHelper.getExistingSignature());
		return getXAdESService().getDataToSign(dataToSignHelper.getToBeSigned(), xadesParameters);
	}

	@Override
	public DSSDocument signDocument(List<DSSDocument> toSignDocuments, ASiCWithXAdESSignatureParameters parameters, SignatureValue signatureValue)
			throws DSSException {
		final ASiCParameters asicParameters = parameters.aSiC();
		assertSigningDateInCertificateValidityRange(parameters);

		GetDataToSignASiCWithXAdESHelper dataToSignHelper = ASiCWithXAdESDataToSignHelperBuilder.getGetDataToSignHelper(toSignDocuments, parameters);

		List<DSSDocument> signatures = dataToSignHelper.getSignatures();
		List<DSSDocument> manifestFiles = dataToSignHelper.getManifestFiles();
		List<DSSDocument> signedDocuments = dataToSignHelper.getSignedDocuments();

		XAdESSignatureParameters xadesParameters = getXAdESParameters(parameters, dataToSignHelper.getExistingSignature());
		final DSSDocument newSignature = getXAdESService().signDocument(dataToSignHelper.getToBeSigned(), xadesParameters, signatureValue);
		String newSignatureFilename = dataToSignHelper.getSignatureFilename();
		newSignature.setName(newSignatureFilename);

		if (ASiCUtils.isASiCS(asicParameters)) {
			Iterator<DSSDocument> iterator = signatures.iterator();
			while (iterator.hasNext()) {
				if (Utils.areStringsEqual(newSignatureFilename, iterator.next().getName())) {
					iterator.remove(); // remove existing file to be replaced
				}
			}
		}
		signatures.add(newSignature);

		final DSSDocument asicSignature = buildASiCContainer(signedDocuments, signatures, manifestFiles, asicParameters);
		asicSignature
				.setName(DSSUtils.getFinalFileName(asicSignature, SigningOperation.SIGN, parameters.getSignatureLevel(), parameters.aSiC().getContainerType()));
		parameters.reinitDeterministicId();
		return asicSignature;
	}

	@Override
	public DSSDocument extendDocument(DSSDocument toExtendDocument, ASiCWithXAdESSignatureParameters parameters) throws DSSException {
		if (!ASiCUtils.isASiCContainer(toExtendDocument) || !ASiCUtils.isArchiveContainsCorrectSignatureFileWithExtension(toExtendDocument, ".xml")) {
			throw new DSSException("Unsupported file type");
		}

		extractCurrentArchive(toExtendDocument);
		List<DSSDocument> signedDocuments = getEmbeddedSignedDocuments();
		List<DSSDocument> signatureDocuments = getEmbeddedSignatures();

		List<DSSDocument> extendedDocuments = new ArrayList<DSSDocument>();

		for (DSSDocument signature : signatureDocuments) {
			XAdESSignatureParameters xadesParameters = getXAdESParameters(parameters, null);
			xadesParameters.setDetachedContents(signedDocuments);
			DSSDocument extendDocument = getXAdESService().extendDocument(signature, xadesParameters);
			extendedDocuments.add(extendDocument);
		}

		DSSDocument extensionResult = mergeArchiveAndExtendedSignatures(toExtendDocument, extendedDocuments);
		extensionResult.setName(
				DSSUtils.getFinalFileName(toExtendDocument, SigningOperation.EXTEND, parameters.getSignatureLevel(), parameters.aSiC().getContainerType()));
		return extensionResult;
	}

	@Override
	boolean isSignatureFilename(String name) {
		return ASiCUtils.isXAdES(name);
	}

	private XAdESService getXAdESService() {
		XAdESService xadesService = new XAdESService(certificateVerifier);
		xadesService.setTspSource(tspSource);
		return xadesService;
	}

	private XAdESSignatureParameters getXAdESParameters(ASiCWithXAdESSignatureParameters parameters, DSSDocument existingXAdESSignatureASiCS) {
		XAdESSignatureParameters xadesParameters = parameters;
		xadesParameters.setSignaturePackaging(SignaturePackaging.DETACHED);
		Document rootDocument = null;
		// If ASiC-S + already existing signature file, we re-use the same signature file
		if (existingXAdESSignatureASiCS != null) {
			rootDocument = DomUtils.buildDOM(existingXAdESSignatureASiCS);
		} else {
			rootDocument = DomUtils.buildDOM();
			final Element xadesSignatures = rootDocument.createElementNS(ASiCNamespace.NS, ASiCNamespace.XADES_SIGNATURES);
			rootDocument.appendChild(xadesSignatures);
		}
		xadesParameters.setRootDocument(rootDocument);
		return xadesParameters;
	}

	@Override
	AbstractASiCContainerExtractor getArchiveExtractor(DSSDocument archive) {
		return new ASiCWithXAdESContainerExtractor(archive);
	}

	@Override
	String getExpectedSignatureExtension() {
		return ".xml";
	}

}
