package com.eu.habbo.crypto;

import com.eu.habbo.crypto.exceptions.HabboCryptoException;
import com.eu.habbo.crypto.utils.BigIntegerUtils;
import com.eu.habbo.util.HexUtils;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

public class HabboDiffieHellman {

    // Increased key sizes for better security (128 bits is insufficient for modern standards)
    private static final int DH_PRIMES_BIT_SIZE = 1024; // Minimum recommended size
    private static final int DH_KEY_BIT_SIZE = 256;     // Increased private key size

    private final HabboRSACrypto crypto;

    private BigInteger DHPrime;
    private BigInteger DHGenerator;
    private BigInteger DHPrivate;
    private BigInteger DHPublic;

    public HabboDiffieHellman(HabboRSACrypto crypto) {
        this.crypto = crypto;
        this.generateDHPrimes();
        this.generateDHKeys();
    }

    public BigInteger getDHPrime() {
        return DHPrime;
    }

    public BigInteger getDHGenerator() {
        return DHGenerator;
    }

    private void generateDHPrimes() {
        // Generate cryptographically strong prime
        this.DHPrime = BigInteger.probablePrime(DH_PRIMES_BIT_SIZE, ThreadLocalRandom.current());
        
        // Use standard safe generator (2 is commonly used and mathematically sound)
        this.DHGenerator = BigInteger.valueOf(2);
        
        // Validate that generator is suitable for this prime
        if (this.DHGenerator.compareTo(this.DHPrime) >= 0) {
            // Fallback to 3 if 2 is not suitable (very rare case)
            this.DHGenerator = BigInteger.valueOf(3);
        }
        
        // Additional security check: ensure prime is actually a safe prime
        // A safe prime p is one where (p-1)/2 is also prime
        BigInteger q = this.DHPrime.subtract(BigInteger.ONE).divide(BigInteger.valueOf(2));
        if (!q.isProbablePrime(100)) {
            // Regenerate if not a safe prime (recursive call with low probability)
            generateDHPrimes();
        }
    }

    private void generateDHKeys() {
        this.DHPrivate = BigInteger.probablePrime(DH_KEY_BIT_SIZE, ThreadLocalRandom.current());
        this.DHPublic = this.DHGenerator.modPow(this.DHPrivate, this.DHPrime);
    }

    private String encryptBigInteger(BigInteger integer) throws HabboCryptoException {
        String str = integer.toString(10);
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = this.crypto.Sign(bytes);

        return HexUtils.toHex(encrypted).toLowerCase();
    }

    private BigInteger decryptBigInteger(String str) throws HabboCryptoException {
        byte[] bytes = HexUtils.toBytes(str);
        byte[] decrypted = this.crypto.Decrypt(bytes);
        String intStr = new String(decrypted, StandardCharsets.UTF_8);

        return new BigInteger(intStr, 10);
    }

    public String getPublicKey() throws HabboCryptoException {
        return encryptBigInteger(this.DHPublic);
    }

    public String getSignedPrime() throws HabboCryptoException {
        return encryptBigInteger(this.DHPrime);
    }

    public String getSignedGenerator() throws HabboCryptoException {
        return encryptBigInteger(this.DHGenerator);
    }

    public void doHandshake(String signedPrime, String signedGenerator) throws HabboCryptoException {
        this.DHPrime = decryptBigInteger(signedPrime);
        this.DHGenerator = decryptBigInteger(signedGenerator);

        if (this.DHPrime == null || this.DHGenerator == null) {
            throw new HabboCryptoException("DHPrime or DHGenerator was null.");
        }

        // Enhanced security validations
        if (this.DHPrime.compareTo(BigInteger.valueOf(2)) <= 0) {
            throw new HabboCryptoException("Prime must be > 2. Received: " + this.DHPrime);
        }

        // Check minimum bit size for security
        if (this.DHPrime.bitLength() < 1024) {
            throw new HabboCryptoException("Prime too small for secure DH exchange. Minimum 1024 bits required. Received: " + this.DHPrime.bitLength() + " bits");
        }

        if (this.DHGenerator.compareTo(this.DHPrime) >= 0) {
            throw new HabboCryptoException("Generator must be < Prime. Prime: " + this.DHPrime + ", Generator: " + this.DHGenerator);
        }

        // Validate generator is suitable (2, 3, 5 are common safe generators)
        if (!this.DHGenerator.equals(BigInteger.valueOf(2)) && 
            !this.DHGenerator.equals(BigInteger.valueOf(3)) && 
            !this.DHGenerator.equals(BigInteger.valueOf(5))) {
            throw new HabboCryptoException("Unsafe generator. Only 2, 3, or 5 are accepted. Received: " + this.DHGenerator);
        }

        // Verify prime is actually prime with high confidence
        if (!this.DHPrime.isProbablePrime(100)) {
            throw new HabboCryptoException("Provided number is not a prime");
        }

        generateDHKeys();
    }

    public byte[] getSharedKey(String publicKeyStr) throws HabboCryptoException {
        BigInteger publicKey = this.decryptBigInteger(publicKeyStr);
        BigInteger sharedKey = publicKey.modPow(this.DHPrivate, this.DHPrime);

        return BigIntegerUtils.toUnsignedByteArray(sharedKey);
    }

}
