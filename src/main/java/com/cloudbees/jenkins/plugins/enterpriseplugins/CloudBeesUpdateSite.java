/*
 * The MIT License
 *
 * Copyright (c) 2011-2013, CloudBees, Inc., Stephen Connolly.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cloudbees.jenkins.plugins.enterpriseplugins;

import hudson.model.UpdateSite;
import org.apache.commons.io.IOUtils;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Set;
import java.util.Collections;
import jenkins.util.JSONSignatureValidator;

/**
 * An update site that uses the CloudBees certificate for signing.
 */
public class CloudBeesUpdateSite extends UpdateSite {

    /**
     * Constructor.
     *
     * @param id  the ID of the update site.
     * @param url the url of the update site.
     */
    public CloudBeesUpdateSite(String id, String url) {
        super(id, url);
    }

    @Override protected JSONSignatureValidator getJsonSignatureValidator() {
        return new JSONSignatureValidator("update site '" + getId() + "'") {
            @Override protected Set<TrustAnchor> loadTrustAnchors(CertificateFactory cf) throws IOException {
                InputStream stream = CloudBeesUpdateSite.class.getResourceAsStream("/cloudbees-root-cacert.pem");
                try {
                    return Collections.singleton(new TrustAnchor((X509Certificate) cf.generateCertificate(stream), null));
                } catch (CertificateException x) {
                    throw new IOException(x);
                } finally {
                    IOUtils.closeQuietly(stream);
                }
            }
        };
    }

}
