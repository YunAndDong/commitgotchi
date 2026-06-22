package com.commitgotchi.character.image;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.TreeMap;

@Component
public class CharacterImageStorageUrlFactory {

    private static final DateTimeFormatter AMZ_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final String TERMINATOR = "aws4_request";
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    private static final String DEFAULT_S3_ENDPOINT_SUFFIX = ".amazonaws.com";
    private static final char[] UPPER_HEX = "0123456789ABCDEF".toCharArray();

    private final CharacterImageProperties properties;
    private final Clock clock;

    @Autowired
    public CharacterImageStorageUrlFactory(CharacterImageProperties properties) {
        this(properties, Clock.systemUTC());
    }

    CharacterImageStorageUrlFactory(CharacterImageProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String createStorageUrl(long userId, long characterId) {
        String s3ObjectUrl = s3ObjectUrl(userId, characterId);
        if (!properties.isS3PresignedUrlEnabled()) {
            return s3ObjectUrl;
        }
        return presignedPutUrl(s3ObjectUrl);
    }

    private String s3ObjectUrl(long userId, long characterId) {
        return "%s/characters/%d/sprite-sheet.png".formatted(
                properties.normalizedS3ObjectPrefix(),
                characterId
        );
    }

    private String presignedPutUrl(String s3ObjectUrl) {
        S3ObjectLocation location = S3ObjectLocation.from(s3ObjectUrl);
        String region = requireText(properties.normalizedS3Region(), "s3Region");
        String accessKeyId = requireText(properties.normalizedS3AccessKeyId(), "s3AccessKeyId");
        String secretAccessKey = requireText(properties.normalizedS3SecretAccessKey(), "s3SecretAccessKey");
        String sessionToken = properties.normalizedS3SessionToken();
        Duration ttl = properties.normalizedS3PresignedUrlTtl();
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("s3PresignedUrlTtl must be positive");
        }
        if (ttl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalStateException("s3PresignedUrlTtl must be 7 days or less");
        }

        PresignTarget target = PresignTarget.from(location, region, properties.normalizedS3EndpointUrl());
        Instant now = Instant.now(clock);
        String amzDate = AMZ_DATE_FORMAT.format(now);
        String dateStamp = DATE_STAMP_FORMAT.format(now);
        String credentialScope = "%s/%s/%s/%s".formatted(dateStamp, region, SERVICE, TERMINATOR);

        TreeMap<String, String> queryParams = new TreeMap<>();
        queryParams.put("X-Amz-Algorithm", ALGORITHM);
        queryParams.put("X-Amz-Credential", accessKeyId + "/" + credentialScope);
        queryParams.put("X-Amz-Date", amzDate);
        queryParams.put("X-Amz-Expires", Long.toString(ttl.toSeconds()));
        queryParams.put("X-Amz-SignedHeaders", "host");
        if (StringUtils.hasText(sessionToken)) {
            queryParams.put("X-Amz-Security-Token", sessionToken);
        }

        String canonicalQueryString = canonicalQueryString(queryParams);
        String canonicalRequest = """
                PUT
                %s
                %s
                host:%s

                host
                %s""".formatted(
                target.canonicalUri(),
                canonicalQueryString,
                target.host(),
                UNSIGNED_PAYLOAD
        );
        String stringToSign = """
                %s
                %s
                %s
                %s""".formatted(
                ALGORITHM,
                amzDate,
                credentialScope,
                sha256Hex(canonicalRequest)
        );
        String signature = hmacSha256Hex(signingKey(secretAccessKey, dateStamp, region), stringToSign);
        return "%s?%s&X-Amz-Signature=%s".formatted(
                target.baseUrlWithCanonicalUri(),
                canonicalQueryString,
                signature
        );
    }

    private static byte[] signingKey(String secretAccessKey, String dateStamp, String region) {
        byte[] dateKey = hmacSha256(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] regionKey = hmacSha256(dateKey, region);
        byte[] serviceKey = hmacSha256(regionKey, SERVICE);
        return hmacSha256(serviceKey, TERMINATOR);
    }

    private static String canonicalQueryString(TreeMap<String, String> queryParams) {
        return queryParams.entrySet().stream()
                .map(entry -> percentEncode(entry.getKey()) + "=" + percentEncode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String hmacSha256Hex(byte[] key, String value) {
        return HexFormat.of().formatHex(hmacSha256(key, value));
    }

    private static byte[] hmacSha256(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("HmacSHA256 is not available", exception);
        } catch (InvalidKeyException exception) {
            throw new IllegalStateException("S3 signing key is invalid", exception);
        }
    }

    private static String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static String percentEncode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = b & 0xff;
            if ((unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-'
                    || unsigned == '_'
                    || unsigned == '.'
                    || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%')
                        .append(UPPER_HEX[(unsigned >> 4) & 0x0f])
                        .append(UPPER_HEX[unsigned & 0x0f]);
            }
        }
        return encoded.toString();
    }

    private static String encodeCanonicalPath(String path) {
        StringBuilder encoded = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (!encoded.isEmpty()) {
                encoded.append('/');
            }
            encoded.append(percentEncode(segment));
        }
        return encoded.toString();
    }

    private static String trimSlashes(String value) {
        String normalized = value == null ? "" : value.strip();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record S3ObjectLocation(String bucket, String key) {

        static S3ObjectLocation from(String s3ObjectUrl) {
            URI uri = URI.create(s3ObjectUrl);
            if (!"s3".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(uri.getHost())) {
                throw new IllegalStateException("s3ObjectPrefix must resolve to an s3://bucket/key URL");
            }
            String key = trimSlashes(uri.getPath());
            if (!StringUtils.hasText(key)) {
                throw new IllegalStateException("S3 object key must not be blank");
            }
            return new S3ObjectLocation(uri.getHost(), key);
        }
    }

    private record PresignTarget(String scheme, String host, String canonicalUri) {

        static PresignTarget from(S3ObjectLocation location, String region, String endpointUrl) {
            if (StringUtils.hasText(endpointUrl)) {
                URI endpoint = URI.create(endpointUrl);
                String host = endpoint.getHost();
                if (!StringUtils.hasText(host)) {
                    throw new IllegalStateException("s3EndpointUrl host must not be blank");
                }
                if (endpoint.getPort() >= 0) {
                    host = host + ":" + endpoint.getPort();
                }
                String basePath = trimSlashes(endpoint.getPath());
                String path = joinPath(basePath, location.bucket(), location.key());
                return new PresignTarget(
                        StringUtils.hasText(endpoint.getScheme()) ? endpoint.getScheme() : "https",
                        host,
                        "/" + encodeCanonicalPath(path)
                );
            }

            String host = "%s.s3.%s%s".formatted(
                    location.bucket(),
                    region,
                    DEFAULT_S3_ENDPOINT_SUFFIX
            );
            return new PresignTarget("https", host, "/" + encodeCanonicalPath(location.key()));
        }

        String baseUrlWithCanonicalUri() {
            return scheme + "://" + host + canonicalUri;
        }

        private static String joinPath(String... parts) {
            StringBuilder joined = new StringBuilder();
            for (String part : parts) {
                String normalized = trimSlashes(part);
                if (!StringUtils.hasText(normalized)) {
                    continue;
                }
                if (!joined.isEmpty()) {
                    joined.append('/');
                }
                joined.append(normalized);
            }
            return joined.toString();
        }
    }
}
