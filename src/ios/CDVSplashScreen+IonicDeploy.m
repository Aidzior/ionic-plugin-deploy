#import "CDVSplashScreen+IonicDeploy.h"
#import <objc/runtime.h>
#import <Cordova/CDVViewController.h>
#import <Cordova/CDVScreenOrientationDelegate.h>

@implementation CDVSplashScreen(Deploy)

- (void)swizzled_setvisible:(BOOL)visible andForce:(BOOL)force
{
    if (!visible){
        dispatch_queue_t serialQueue = dispatch_queue_create("Deploy SetVis Queue", NULL);
        dispatch_async(serialQueue, ^{
            NSLog(@"DEPLOY: Waiting to hide splash");
            while ([IonicDeploy isPluginUpdating]) {
                [NSThread sleepForTimeInterval:0.5f];
            }
            [self swizzled_setvisible:visible andForce:force];
        });
    }
    [self swizzled_setvisible:visible andForce:force];
}

- (void)swizzled_pageDidLoad
{
    dispatch_queue_t serialQueue = dispatch_queue_create("Deploy Load Queue", NULL);
    dispatch_async(serialQueue, ^{
        NSLog(@"DEPLOY: Waiting to hide splash");
        while ([IonicDeploy isPluginUpdating]) {
            [NSThread sleepForTimeInterval:0.5f];
        }
        [self swizzled_pageDidLoad];
    });
}

+ (void)swizzle:(SEL)originalSelector newImp:(SEL)swizzledSelector
{
    Class class = [self class];
    Method originalMethod = class_getInstanceMethod(class, originalSelector);
    Method swizzledMethod = class_getInstanceMethod(class, swizzledSelector);
    BOOL didAddMethod = class_addMethod(class,
                                        originalSelector,
                                        method_getImplementation(swizzledMethod),
                                        method_getTypeEncoding(swizzledMethod));
    
    if (didAddMethod) {
        class_replaceMethod(class,
                            swizzledSelector,
                            method_getImplementation(originalMethod),
                            method_getTypeEncoding(originalMethod));
    } else {
        method_exchangeImplementations(originalMethod, swizzledMethod);
    }
}

+ (void)load
{
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        Class class = [self class];
        
        SEL originalVis = @selector(setVisible: andForce:);
        SEL swizzledVis = @selector(swizzled_setvisible: andForce:);
        [class swizzle:originalVis newImp:swizzledVis];
        
        SEL originalLoad = @selector(pageDidLoad);
        SEL swizzledLoad = @selector(swizzled_pageDidLoad);
        [class swizzle:originalLoad newImp:swizzledLoad];
    });
}
@end
