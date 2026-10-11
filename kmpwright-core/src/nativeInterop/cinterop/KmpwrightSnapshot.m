#import "KmpwrightSnapshot.h"
#import <CommonCrypto/CommonCryptor.h>
#import <CommonCrypto/CommonDigest.h>
#import <CommonCrypto/CommonHMAC.h>
#import <CommonCrypto/CommonKeyDerivation.h>
#import <Security/Security.h>
#import <WebKit/WebKit.h>

static NSString *const WWEncryptionPrefix = @"WW2:";
static const size_t WWSaltSize = 16;
static const size_t WWIVSize = 16;
static const size_t WWTagSize = CC_SHA256_DIGEST_LENGTH;
static const uint WWPBKDFRounds = 210000;

@implementation KmpwrightSnapshot

+ (void)capturePngFromWebView:(WKWebView *)webView
                  completion:(void (^)(NSData * _Nullable pngData, NSError * _Nullable error))completion
{
    if (!completion) return;

    dispatch_async(dispatch_get_main_queue(), ^{
        WKSnapshotConfiguration *config = [[WKSnapshotConfiguration alloc] init];

        [webView takeSnapshotWithConfiguration:config completionHandler:^(
            UIImage * _Nullable image,
            NSError * _Nullable error
        ) {
            if (error || !image) {
                completion(nil, error);
                return;
            }

            NSData *png = UIImagePNGRepresentation(image);
            completion(png, png ? nil : [NSError errorWithDomain:@"KmpwrightSnapshot"
                                                             code:-1
                                                         userInfo:@{NSLocalizedDescriptionKey: @"PNG encode failed"}]);
        }];
    });
}

+ (nullable NSString *)encryptString:(NSString *)plainText password:(NSString *)password
{
    if (password.length == 0) return nil;
    NSMutableData *salt = [NSMutableData dataWithLength:WWSaltSize];
    NSMutableData *iv = [NSMutableData dataWithLength:WWIVSize];
    if (SecRandomCopyBytes(kSecRandomDefault, salt.length, salt.mutableBytes) != errSecSuccess ||
        SecRandomCopyBytes(kSecRandomDefault, iv.length, iv.mutableBytes) != errSecSuccess) return nil;

    NSData *passwordData = [password dataUsingEncoding:NSUTF8StringEncoding];
    NSMutableData *keyMaterial = [NSMutableData dataWithLength:kCCKeySizeAES256 * 2];
    if (CCKeyDerivationPBKDF(kCCPBKDF2, passwordData.bytes, passwordData.length,
                            salt.bytes, salt.length, kCCPRFHmacAlgSHA256,
                            WWPBKDFRounds, keyMaterial.mutableBytes, keyMaterial.length) != kCCSuccess) return nil;

    NSData *plainData = [plainText dataUsingEncoding:NSUTF8StringEncoding];
    NSMutableData *encrypted = [NSMutableData dataWithLength:plainData.length + kCCBlockSizeAES128];
    size_t encryptedLength = 0;
    CCCryptorStatus status = CCCrypt(kCCEncrypt, kCCAlgorithmAES, kCCOptionPKCS7Padding,
                                     keyMaterial.bytes, kCCKeySizeAES256, iv.bytes,
                                     plainData.bytes, plainData.length, encrypted.mutableBytes,
                                     encrypted.length, &encryptedLength);
    if (status != kCCSuccess) return nil;
    encrypted.length = encryptedLength;

    NSMutableData *authenticated = [NSMutableData dataWithData:salt];
    [authenticated appendData:iv];
    [authenticated appendData:encrypted];
    NSMutableData *tag = [NSMutableData dataWithLength:WWTagSize];
    CCHmac(kCCHmacAlgSHA256, (const uint8_t *)keyMaterial.bytes + kCCKeySizeAES256,
           kCCKeySizeAES256, authenticated.bytes, authenticated.length, tag.mutableBytes);
    [authenticated appendData:tag];
    return [WWEncryptionPrefix stringByAppendingString:[authenticated base64EncodedStringWithOptions:0]];
}

+ (nullable NSString *)decryptString:(NSString *)cipherText password:(NSString *)password
{
    if (password.length == 0 || ![cipherText hasPrefix:WWEncryptionPrefix]) return nil;
    NSData *combined = [[NSData alloc] initWithBase64EncodedString:[cipherText substringFromIndex:WWEncryptionPrefix.length]
                                                           options:0];
    if (combined.length <= WWSaltSize + WWIVSize + WWTagSize) return nil;
    const uint8_t *bytes = combined.bytes;
    NSData *salt = [NSData dataWithBytes:bytes length:WWSaltSize];
    NSData *iv = [NSData dataWithBytes:bytes + WWSaltSize length:WWIVSize];
    size_t encryptedLength = combined.length - WWSaltSize - WWIVSize - WWTagSize;
    NSData *encrypted = [NSData dataWithBytes:bytes + WWSaltSize + WWIVSize length:encryptedLength];
    NSData *tag = [NSData dataWithBytes:bytes + combined.length - WWTagSize length:WWTagSize];

    NSData *passwordData = [password dataUsingEncoding:NSUTF8StringEncoding];
    NSMutableData *keyMaterial = [NSMutableData dataWithLength:kCCKeySizeAES256 * 2];
    if (CCKeyDerivationPBKDF(kCCPBKDF2, passwordData.bytes, passwordData.length,
                            salt.bytes, salt.length, kCCPRFHmacAlgSHA256,
                            WWPBKDFRounds, keyMaterial.mutableBytes, keyMaterial.length) != kCCSuccess) return nil;

    NSMutableData *expectedTag = [NSMutableData dataWithLength:WWTagSize];
    NSData *authenticated = [combined subdataWithRange:NSMakeRange(0, combined.length - WWTagSize)];
    CCHmac(kCCHmacAlgSHA256, (const uint8_t *)keyMaterial.bytes + kCCKeySizeAES256,
           kCCKeySizeAES256, authenticated.bytes, authenticated.length, expectedTag.mutableBytes);
    const uint8_t *expectedBytes = expectedTag.bytes;
    const uint8_t *tagBytes = tag.bytes;
    uint8_t difference = 0;
    for (NSUInteger i = 0; i < WWTagSize; i++) difference |= expectedBytes[i] ^ tagBytes[i];
    if (difference != 0) return nil;

    NSMutableData *plain = [NSMutableData dataWithLength:encryptedLength + kCCBlockSizeAES128];
    size_t plainLength = 0;
    CCCryptorStatus status = CCCrypt(kCCDecrypt, kCCAlgorithmAES, kCCOptionPKCS7Padding,
                                     keyMaterial.bytes, kCCKeySizeAES256, iv.bytes,
                                     encrypted.bytes, encrypted.length, plain.mutableBytes,
                                     plain.length, &plainLength);
    if (status != kCCSuccess) return nil;
    plain.length = plainLength;
    return [[NSString alloc] initWithData:plain encoding:NSUTF8StringEncoding];
}

+ (nullable NSData *)randomDataOfLength:(NSUInteger)length
{
    NSMutableData *data = [NSMutableData dataWithLength:length];
    return SecRandomCopyBytes(kSecRandomDefault, length, data.mutableBytes) == errSecSuccess ? data : nil;
}

@end
