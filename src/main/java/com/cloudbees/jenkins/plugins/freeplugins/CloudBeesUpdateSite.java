package com.cloudbees.jenkins.plugins.freeplugins;

import com.trilead.ssh2.crypto.Base64;
import hudson.model.UpdateSite;
import hudson.util.FormValidation;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.io.output.TeeOutputStream;
import org.jvnet.hudson.crypto.CertificateUtil;
import org.jvnet.hudson.crypto.SignatureOutputStream;

import javax.servlet.ServletContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by IntelliJ IDEA.
 * User: stephenc
 * Date: 16/12/2011
 * Time: 14:04
 * To change this template use File | Settings | File Templates.
 */
public class CloudBeesUpdateSite extends UpdateSite {
    public CloudBeesUpdateSite(String id, String url) {
        super(id, url);
    }

    public FormValidation doVerifySignature() throws IOException {
        return verifySignature(getJSONObject());
    }

    /**
     * Verifies the signature in the update center data file.
     */
    private FormValidation verifySignature(JSONObject o) throws IOException {
        try {
            FormValidation warning = null;

            JSONObject signature = o.getJSONObject("signature");
            if (signature.isNullObject()) {
                return FormValidation.error("No signature block found in update center '"+getId()+"'");
            }
            o.remove("signature");

            List<X509Certificate> certs = new ArrayList<X509Certificate>();
            {// load and verify certificates
                CertificateFactory cf = CertificateFactory.getInstance("X509");
                for (Object cert : signature.getJSONArray("certificates")) {
                    X509Certificate c = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(
                            Base64.decode(cert.toString().toCharArray())));
                    try {
                        c.checkValidity();
                    } catch (CertificateExpiredException e) { // even if the certificate isn't valid yet, we'll proceed it anyway
                        warning = FormValidation.warning(e,String.format("Certificate %s has expired in update center '%s'",cert.toString(),getId()));
                    } catch (CertificateNotYetValidException e) {
                        warning = FormValidation.warning(e,String.format("Certificate %s is not yet valid in update center '%s'",cert.toString(),getId()));
                    }
                    certs.add(c);
                }

                // all default root CAs in JVM are trusted, plus certs bundled in Jenkins
                Set<TrustAnchor> anchors = new HashSet<TrustAnchor>(); // CertificateUtil.getDefaultRootCAs();
                ServletContext context = Jenkins.getInstance().servletContext;
                anchors.add(new TrustAnchor(loadLicenseCaCertificate(), null));
                for (String cert : (Set<String>) context.getResourcePaths("/WEB-INF/update-center-rootCAs")) {
                    if (cert.endsWith(".txt"))  continue;       // skip text files that are meant to be documentation
                    anchors.add(new TrustAnchor((X509Certificate)cf.generateCertificate(context.getResourceAsStream
                            (cert)),null));
                }
                CertificateUtil.validatePath(certs, anchors);
            }

            // this is for computing a digest to check sanity
            MessageDigest sha1 = MessageDigest.getInstance("SHA1");
            DigestOutputStream dos = new DigestOutputStream(new NullOutputStream(),sha1);

            // this is for computing a signature
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initVerify(certs.get(0));
            SignatureOutputStream sos = new SignatureOutputStream(sig);

            // until JENKINS-11110 fix, UC used to serve invalid digest (and therefore unverifiable signature)
            // that only covers the earlier portion of the file. This was caused by the lack of close() call
            // in the canonical writing, which apparently leave some bytes somewhere that's not flushed to
            // the digest output stream. This affects Jenkins [1.424,1,431].
            // Jenkins 1.432 shipped with the "fix" (1eb0c64abb3794edce29cbb1de50c93fa03a8229) that made it
            // compute the correct digest, but it breaks all the existing UC json metadata out there. We then
            // quickly discovered ourselves in the catch-22 situation. If we generate UC with the correct signature,
            // it'll cut off [1.424,1.431] from the UC. But if we don't, we'll cut off [1.432,*).
            //
            // In 1.433, we revisited 1eb0c64abb3794edce29cbb1de50c93fa03a8229 so that the original "digest"/"signature"
            // pair continues to be generated in a buggy form, while "correct_digest"/"correct_signature" are generated
            // correctly.
            //
            // Jenkins should ignore "digest"/"signature" pair. Accepting it creates a vulnerability that allows
            // the attacker to inject a fragment at the end of the json.
            o.writeCanonical(new OutputStreamWriter(new TeeOutputStream(dos,sos),"UTF-8")).close();

            // did the digest match? this is not a part of the signature validation, but if we have a bug in the c14n
            // (which is more likely than someone tampering with update center), we can tell
            String computedDigest = new String(Base64.encode(sha1.digest()));
            String providedDigest = signature.optString("correct_digest");
            if (providedDigest==null) {
                return FormValidation.error("No correct_digest parameter in update center '"+getId()+"'. This metadata appears to be old.");
            }
            if (!computedDigest.equalsIgnoreCase(providedDigest)) {
                return FormValidation.error("Digest mismatch: "+computedDigest+" vs "+providedDigest+" in update center '"+getId()+"'");
            }

            String providedSignature = signature.getString("correct_signature");
            if (!sig.verify(Base64.decode(providedSignature.toCharArray()))) {
                return FormValidation.error("Signature in the update center doesn't match with the certificate in update center '"+getId()+"'");
            }

            if (warning!=null)  return warning;
            return FormValidation.ok();
        } catch (GeneralSecurityException e) {
            return FormValidation.error(e,"Signature verification failed in the update center '"+getId()+"'");
        }
    }

    /*package*/ static X509Certificate loadLicenseCaCertificate() throws CertificateException {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(CloudBeesUpdateSite.class.getResourceAsStream("/cloudbees-root-cacert.pem"));
    }

}
