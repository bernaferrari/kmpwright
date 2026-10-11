#import <Foundation/Foundation.h>
#import <WebKit/WebKit.h>

NS_ASSUME_NONNULL_BEGIN

@interface KmpwrightSnapshot : NSObject

+ (void)capturePngFromWebView:(WKWebView *)webView
                  completion:(void (^)(NSData * _Nullable pngData, NSError * _Nullable error))completion;

+ (nullable NSString *)encryptString:(NSString *)plainText password:(NSString *)password;
+ (nullable NSString *)decryptString:(NSString *)cipherText password:(NSString *)password;
+ (nullable NSData *)randomDataOfLength:(NSUInteger)length;

@end

NS_ASSUME_NONNULL_END
