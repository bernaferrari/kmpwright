#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

@interface KmpwrightSnapshot : NSObject

+ (nullable NSString *)encryptString:(NSString *)plainText password:(NSString *)password;
+ (nullable NSString *)decryptString:(NSString *)cipherText password:(NSString *)password;
+ (nullable NSData *)randomDataOfLength:(NSUInteger)length;

@end

NS_ASSUME_NONNULL_END
