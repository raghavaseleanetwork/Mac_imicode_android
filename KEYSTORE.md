# Keystore Documentation

## Overview

The keystore is used to digitally sign Android APK files for release builds. This document provides complete information about keystore setup, configuration, and usage in the IMI-GLASS-APP project.

## Keystore File Details

### Location
- **Path**: `app/imi_release.keystore`
- **Type**: Java KeyStore (JKS) - Binary format
- **Purpose**: Contains private key and certificate for APK signing

### File Information
- **Format**: JKS (Java KeyStore)
- **Size**: Binary data file
- **Visibility**: Should be kept secure and not committed to public repositories

## Signing Configuration

### Build Configuration

The keystore is configured in [app/build.gradle](app/build.gradle#L51-L58):

```gradle
signingConfigs {
    release {
        storeFile rootProject.file(localProperties["KEYSTORE_FILE"] ?: "app/imi_release.keystore")
        storePassword localProperties["KEYSTORE_PASSWORD"] ?: "imi123"
        keyAlias localProperties["KEY_ALIAS"] ?: "imi_key"
        keyPassword localProperties["KEY_PASSWORD"] ?: "imi123"
    }
}
```

### Build Type Assignment

The release signing config is applied to release builds ([app/build.gradle:60-66](app/build.gradle#L60-L66)):

```gradle
buildTypes {
    release {
        signingConfig signingConfigs.release
        minifyEnabled true
        shrinkResources true
        proguardFiles getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
    }
    debug {
        minifyEnabled false
    }
}
```

## Keystore Credentials

### Default Credentials

The following are the default fallback credentials (used if not overridden in `local.properties`):

| Property | Value |
|----------|-------|
| Store Password | `imi123` |
| Key Alias | `imi_key` |
| Key Password | `imi123` |

### Configuration Priority

Credentials are loaded in the following order:

1. **First Priority**: Values from `local.properties` file
2. **Fallback**: Default hardcoded values
   - Store Password: `imi123`
   - Key Alias: `imi_key`
   - Key Password: `imi123`

### Loading Mechanism

The `local.properties` file is read at build time:

```gradle
def localProperties = new Properties()
def localPropertiesFile = rootProject.file('local.properties')
if (localPropertiesFile.exists()) {
    localProperties.load(new FileInputStream(localPropertiesFile))
}
```

## Configuration via local.properties

To override default keystore credentials, add the following properties to `local.properties`:

```properties
# Keystore Configuration
KEYSTORE_FILE=/path/to/your/keystore.jks
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=your_key_alias
KEY_PASSWORD=your_key_password
```

### Example Configuration

```properties
sdk.dir=/Users/tanayshrivastava/Library/Android/sdk
KEYSTORE_FILE=app/imi_release.keystore
KEYSTORE_PASSWORD=SecurePassword123!
KEY_ALIAS=imi_release_key
KEY_PASSWORD=SecureKeyPassword456!
```

## Build Process

### Release APK Signing

When building a release APK, the keystore is used to:

1. **Sign the APK** - Digitally signs the APK file with the private key
2. **Verify Integrity** - Ensures APK hasn't been tampered with
3. **Identify Publisher** - Identifies the app publisher

### Build Command

```bash
# Build and sign release APK
./gradlew assembleRelease

# Build and sign release bundle (for Play Store)
./gradlew bundleRelease
```

## Keystore Management

### Creating a New Keystore

If you need to generate a new keystore file:

```bash
keytool -genkey -v -keystore imi_release.keystore \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias imi_key -storepass imi123 -keypass imi123 \
  -dname "CN=Your Name, O=Your Organization, L=City, ST=State, C=Country"
```

### Viewing Keystore Information

To view the contents of the keystore:

```bash
keytool -list -v -keystore app/imi_release.keystore \
  -storepass imi123 -alias imi_key
```

### Exporting Certificate

To export the certificate from the keystore:

```bash
keytool -export -alias imi_key -keystore app/imi_release.keystore \
  -storepass imi123 -file imi_release.cer
```

## Security Considerations

### ⚠️ Important Security Notes

1. **Private Key Protection**
   - The keystore file contains a private key
   - Keep the keystore file secure and never commit to public repositories
   - Use strong passwords for keystore and key access

2. **Default Credentials Risk**
   - Default credentials (`imi123`) should NOT be used in production
   - Always override with strong credentials via `local.properties`
   - Never share keystore passwords or files

3. **local.properties Security**
   - Add `local.properties` to `.gitignore` if it contains sensitive data
   - Use environment variables or secure vaults for production deployments

4. **Key Loss Prevention**
   - Back up the keystore file securely
   - Store backups in a secure location
   - Losing the keystore means inability to update the app on Play Store

### Production Deployment

For production releases:

1. Use strong, unique passwords (minimum 12 characters, mixed case, numbers, symbols)
2. Store keystore file in a secure, encrypted location
3. Restrict access to keystore credentials
4. Use environment variables or CI/CD secrets management
5. Never commit credentials to version control

## Firebase Integration

The project also uses Firebase Remote Config for additional API key storage ([app/build.gradle:174-177](app/build.gradle#L174-L177)):

```gradle
// Firebase Remote Config (for secure API key storage)
implementation platform('com.google.firebase:firebase-bom:33.0.0')
implementation 'com.google.firebase:firebase-config-ktx'
implementation 'com.google.firebase:firebase-analytics-ktx'
```

This provides an additional layer of security for sensitive configuration outside of the APK.

## Troubleshooting

### Build Fails with Keystore Error

**Error**: `Keystore file not found` or `password incorrect`

**Solution**:
1. Verify keystore file exists at the specified path
2. Check credentials in `local.properties` are correct
3. Run: `keytool -list -keystore app/imi_release.keystore` to verify access

### Signing Configuration Errors

**Error**: `alias does not exist`

**Solution**:
1. Verify key alias matches entry in keystore
2. Check `local.properties` has correct `KEY_ALIAS` value
3. List keystore contents: `keytool -list -v -keystore app/imi_release.keystore`

### Gradle Build Issues

**Error**: `Failed to read key from keystore`

**Solution**:
1. Ensure `local.properties` file exists and is readable
2. Verify all keystore properties are set correctly
3. Check file permissions on keystore file
4. Try running: `./gradlew --refresh-dependencies clean assembleRelease`

## Related Configuration Files

- **[app/build.gradle](app/build.gradle)** - Contains signing configuration
- **local.properties** - Override keystore credentials (not tracked in git)
- **[build.gradle.kts](build.gradle.kts)** - Project-level Gradle configuration
- **[PRIVACY_POLICY.md](PRIVACY_POLICY.md)** - References secure keystore usage

## Additional Resources

- [Android Official Keystore Documentation](https://developer.android.com/studio/publish/app-signing)
- [Java Keytool Documentation](https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html)
- [Gradle Android Plugin - Signing Config](https://developer.android.com/reference/tools/gradle-api/7.4/com/android/build/api/dsl/ApkSigningConfig)

---

**Last Updated**: 2026-06-26  
**Project**: IMI-GLASS-APP  
**Version**: 1.9
