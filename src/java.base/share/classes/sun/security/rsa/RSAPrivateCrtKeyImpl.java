/*
 * Copyright (c) 2003, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
/*
 * ===========================================================================
 * (c) Copyright IBM Corp. 2019, 2019 All Rights Reserved
 * ===========================================================================
 */

package sun.security.rsa;

import java.io.IOException;
import java.math.BigInteger;
import java.util.concurrent.ConcurrentLinkedQueue;

import java.security.*;
import java.security.spec.*;
import java.security.interfaces.*;

import sun.security.util.*;

import sun.security.x509.AlgorithmId;
import sun.security.pkcs.PKCS8Key;

import static sun.security.rsa.RSAUtil.KeyType;
import jdk.crypto.jniprovider.NativeCrypto;

/**
 * RSA private key implementation for "RSA", "RSASSA-PSS" algorithms in CRT form.
 * For non-CRT private keys, see RSAPrivateKeyImpl. We need separate classes
 * to ensure correct behavior in instanceof checks, etc.
 *
 * Note: RSA keys must be at least 512 bits long
 *
 * @see RSAPrivateKeyImpl
 * @see RSAKeyFactory
 *
 * @since   1.5
 * @author  Andreas Sterbenz
 */
public final class RSAPrivateCrtKeyImpl
        extends PKCS8Key implements RSAPrivateCrtKey {

    @java.io.Serial
    private static final long serialVersionUID = -1326088454257084918L;

    private final ConcurrentLinkedQueue<Long> keyQ = new ConcurrentLinkedQueue<>();

    private BigInteger n;       // modulus
    private BigInteger e;       // public exponent
    private BigInteger d;       // private exponent
    private BigInteger p;       // prime p
    private BigInteger q;       // prime q
    private BigInteger pe;      // prime exponent p
    private BigInteger qe;      // prime exponent q
    private BigInteger coeff;   // CRT coeffcient

    // Optional parameters associated with this RSA key
    // specified in the encoding of its AlgorithmId.
    // Must be null for "RSA" keys.
    private AlgorithmParameterSpec keyParams;

    private static NativeCrypto nativeCrypto;

    static {
        nativeCrypto = NativeCrypto.getNativeCrypto();
    }

    /**
     * Generate a new key from its encoding. Returns a CRT key if possible
     * and a non-CRT key otherwise. Used by RSAKeyFactory.
     */
    public static RSAPrivateKey newKey(byte[] encoded)
            throws InvalidKeyException {
        RSAPrivateCrtKeyImpl key = new RSAPrivateCrtKeyImpl(encoded);
        // check all CRT-specific components are available, if any one
        // missing, return a non-CRT key instead
        if ((key.getPublicExponent().signum() == 0) ||
            (key.getPrimeExponentP().signum() == 0) ||
            (key.getPrimeExponentQ().signum() == 0) ||
            (key.getPrimeP().signum() == 0) ||
            (key.getPrimeQ().signum() == 0) ||
            (key.getCrtCoefficient().signum() == 0)) {
            return new RSAPrivateKeyImpl(
                key.algid,
                key.getModulus(),
                key.getPrivateExponent()
            );
        } else {
            return key;
        }
    }

    /**
     * Generate a new key from the specified type and components.
     * Returns a CRT key if possible and a non-CRT key otherwise.
     * Used by SunPKCS11 provider.
     */
    public static RSAPrivateKey newKey(KeyType type,
            AlgorithmParameterSpec params,
            BigInteger n, BigInteger e, BigInteger d,
            BigInteger p, BigInteger q, BigInteger pe, BigInteger qe,
            BigInteger coeff) throws InvalidKeyException {
        RSAPrivateKey key;
        AlgorithmId rsaId = RSAUtil.createAlgorithmId(type, params);
        if ((e.signum() == 0) || (p.signum() == 0) ||
            (q.signum() == 0) || (pe.signum() == 0) ||
            (qe.signum() == 0) || (coeff.signum() == 0)) {
            // if any component is missing, return a non-CRT key
            return new RSAPrivateKeyImpl(rsaId, n, d);
        } else {
            return new RSAPrivateCrtKeyImpl(rsaId, n, e, d,
                p, q, pe, qe, coeff);
        }
    }

    /**
     * Construct a key from its encoding. Called from newKey above.
     */
    RSAPrivateCrtKeyImpl(byte[] encoded) throws InvalidKeyException {
        if (encoded == null || encoded.length == 0) {
            throw new InvalidKeyException("Missing key encoding");
        }

        decode(encoded);
        RSAKeyFactory.checkRSAProviderKeyLengths(n.bitLength(), e);
        try {
            // this will check the validity of params
            this.keyParams = RSAUtil.getParamSpec(algid);
        } catch (ProviderException e) {
            throw new InvalidKeyException(e);
        }
    }

    /**
     * Construct a RSA key from its components. Used by the
     * RSAKeyFactory and the RSAKeyPairGenerator.
     */
    RSAPrivateCrtKeyImpl(AlgorithmId rsaId,
            BigInteger n, BigInteger e, BigInteger d,
            BigInteger p, BigInteger q, BigInteger pe, BigInteger qe,
            BigInteger coeff) throws InvalidKeyException {
        RSAKeyFactory.checkRSAProviderKeyLengths(n.bitLength(), e);

        this.n = n;
        this.e = e;
        this.d = d;
        this.p = p;
        this.q = q;
        this.pe = pe;
        this.qe = qe;
        this.coeff = coeff;
        this.keyParams = RSAUtil.getParamSpec(rsaId);

        // generate the encoding
        algid = rsaId;
        try {
            DerOutputStream out = new DerOutputStream();
            out.putInteger(0); // version must be 0
            out.putInteger(n);
            out.putInteger(e);
            out.putInteger(d);
            out.putInteger(p);
            out.putInteger(q);
            out.putInteger(pe);
            out.putInteger(qe);
            out.putInteger(coeff);
            DerValue val =
                new DerValue(DerValue.tag_Sequence, out.toByteArray());
            key = val.toByteArray();
        } catch (IOException exc) {
            // should never occur
            throw new InvalidKeyException(exc);
        }
    }

    // see JCA doc
    @Override
    public String getAlgorithm() {
        return algid.getName();
    }

    // see JCA doc
    @Override
    public BigInteger getModulus() {
        return n;
    }

    // see JCA doc
    @Override
    public BigInteger getPublicExponent() {
        return e;
    }

    // see JCA doc
    @Override
    public BigInteger getPrivateExponent() {
        return d;
    }

    // see JCA doc
    @Override
    public BigInteger getPrimeP() {
        return p;
    }

    // see JCA doc
    @Override
    public BigInteger getPrimeQ() {
        return q;
    }

    // see JCA doc
    @Override
    public BigInteger getPrimeExponentP() {
        return pe;
    }

    // see JCA doc
    @Override
    public BigInteger getPrimeExponentQ() {
        return qe;
    }

    // see JCA doc
    @Override
    public BigInteger getCrtCoefficient() {
        return coeff;
    }

    private long RSAPrivateKey_generate() {

        BigInteger n =    this.getModulus();
        BigInteger d =    this.getPrivateExponent();
        BigInteger e =    this.getPublicExponent();
        BigInteger p =    this.getPrimeP();
        BigInteger q =    this.getPrimeQ();
        BigInteger dP =   this.getPrimeExponentP();
        BigInteger dQ =   this.getPrimeExponentQ();
        BigInteger qInv = this.getCrtCoefficient();

        byte[] n_2c = n.toByteArray();
        byte[] d_2c = d.toByteArray();
        byte[] e_2c = e.toByteArray();

        byte[] p_2c = p.toByteArray();
        byte[] q_2c = q.toByteArray();

        byte[] dP_2c   = dP.toByteArray();
        byte[] dQ_2c   = dQ.toByteArray();
        byte[] qInv_2c = qInv.toByteArray();

        return nativeCrypto.createRSAPrivateCrtKey(n_2c, n_2c.length, d_2c, d_2c.length, e_2c, e_2c.length,
                p_2c, p_2c.length, q_2c, q_2c.length,
                dP_2c, dP_2c.length, dQ_2c, dQ_2c.length, qInv_2c, qInv_2c.length);
    }

    protected long getNativePtr() {
        Long ptr = keyQ.poll();
        if (ptr == null) {
            return RSAPrivateKey_generate();
        }
        return ptr;
    }

    protected void returnNativePtr(long ptr) {
        keyQ.add(ptr);
    }

    @Override
    public void finalize() {
        Long itr;
        while ((itr = keyQ.poll()) != null) {
            nativeCrypto.destroyRSAKey(itr);
        }
    }

    // see JCA doc
    @Override
    public AlgorithmParameterSpec getParams() {
        return keyParams;
    }

    // return a string representation of this key for debugging
    @Override
    public String toString() {
        return "SunRsaSign " + getAlgorithm() + " private CRT key, " + n.bitLength()
               + " bits" + "\n  params: " + keyParams + "\n  modulus: " + n
               + "\n  private exponent: " + d;
    }

    /**
     * Parse the key. Called by PKCS8Key.
     */
    protected void parseKeyBits() throws InvalidKeyException {
        try {
            DerInputStream in = new DerInputStream(key);
            DerValue derValue = in.getDerValue();
            if (derValue.tag != DerValue.tag_Sequence) {
                throw new IOException("Not a SEQUENCE");
            }
            DerInputStream data = derValue.data;
            int version = data.getInteger();
            if (version != 0) {
                throw new IOException("Version must be 0");
            }

            /*
             * Some implementations do not correctly encode ASN.1 INTEGER values
             * in 2's complement format, resulting in a negative integer when
             * decoded. Correct the error by converting it to a positive integer.
             *
             * See CR 6255949
             */
            n = data.getPositiveBigInteger();
            e = data.getPositiveBigInteger();
            d = data.getPositiveBigInteger();
            p = data.getPositiveBigInteger();
            q = data.getPositiveBigInteger();
            pe = data.getPositiveBigInteger();
            qe = data.getPositiveBigInteger();
            coeff = data.getPositiveBigInteger();
            if (derValue.data.available() != 0) {
                throw new IOException("Extra data available");
            }
        } catch (IOException e) {
            throw new InvalidKeyException("Invalid RSA private key", e);
        }
    }
}
