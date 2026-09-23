#import <Foundation/Foundation.h>
#import <MediaPlayer/MediaPlayer.h>
#import <jni.h>

static JavaVM *javaVM;
static jobject receiver;
static jmethodID commandMethod;
static id toggleTarget;
static id playTarget;
static id pauseTarget;
static id previousTarget;
static id nextTarget;

static void sendCommand(jint command) {
    @synchronized ([MPRemoteCommandCenter class]) {
        if (receiver == NULL) {
            return;
        }
        JNIEnv *env = NULL;
        BOOL attached = NO;
        if ((*javaVM)->GetEnv(javaVM, (void **) &env, JNI_VERSION_1_8) == JNI_EDETACHED) {
            if ((*javaVM)->AttachCurrentThread(javaVM, (void **) &env, NULL) != JNI_OK) {
                return;
            }
            attached = YES;
        }
        (*env)->CallVoidMethod(env, receiver, commandMethod, command);
        if (attached) {
            (*javaVM)->DetachCurrentThread(javaVM);
        }
    }
}

static NSString *javaString(JNIEnv *env, jstring value) {
    if (value == NULL) {
        return @"";
    }
    const char *utf8 = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf8 == NULL) {
        return @"";
    }
    NSString *result = [NSString stringWithUTF8String:utf8] ?: @"";
    (*env)->ReleaseStringUTFChars(env, value, utf8);
    return result;
}

JNIEXPORT jboolean JNICALL Java_com_hvlplayer_MacMediaKeys_nativeStart(
        JNIEnv *env, jobject self) {
    @autoreleasepool {
        @synchronized ([MPRemoteCommandCenter class]) {
            if (receiver != NULL) {
                return JNI_TRUE;
            }
            if ((*env)->GetJavaVM(env, &javaVM) != JNI_OK) {
                return JNI_FALSE;
            }
            jclass type = (*env)->GetObjectClass(env, self);
            commandMethod = (*env)->GetMethodID(env, type, "onCommand", "(I)V");
            if (commandMethod == NULL) {
                return JNI_FALSE;
            }
            receiver = (*env)->NewGlobalRef(env, self);

            MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];
            toggleTarget = [center.togglePlayPauseCommand addTargetWithHandler:
                    ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
                sendCommand(0);
                return MPRemoteCommandHandlerStatusSuccess;
            }];
            playTarget = [center.playCommand addTargetWithHandler:
                    ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
                sendCommand(1);
                return MPRemoteCommandHandlerStatusSuccess;
            }];
            pauseTarget = [center.pauseCommand addTargetWithHandler:
                    ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
                sendCommand(2);
                return MPRemoteCommandHandlerStatusSuccess;
            }];
            previousTarget = [center.previousTrackCommand addTargetWithHandler:
                    ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
                sendCommand(3);
                return MPRemoteCommandHandlerStatusSuccess;
            }];
            nextTarget = [center.nextTrackCommand addTargetWithHandler:
                    ^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *event) {
                sendCommand(4);
                return MPRemoteCommandHandlerStatusSuccess;
            }];
            return JNI_TRUE;
        }
    }
}

JNIEXPORT void JNICALL Java_com_hvlplayer_MacMediaKeys_nativeUpdate(
        JNIEnv *env, jobject self, jstring title, jstring artist,
        jdouble duration, jdouble elapsed, jint state) {
    @autoreleasepool {
        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        if (state == 0) {
            center.playbackState = MPNowPlayingPlaybackStateStopped;
            center.nowPlayingInfo = nil;
            return;
        }
        center.nowPlayingInfo = @{
            MPMediaItemPropertyTitle: javaString(env, title),
            MPMediaItemPropertyArtist: javaString(env, artist),
            MPMediaItemPropertyPlaybackDuration: @(duration),
            MPNowPlayingInfoPropertyElapsedPlaybackTime: @(elapsed),
            MPNowPlayingInfoPropertyPlaybackRate: @(state == 1 ? 1.0 : 0.0)
        };
        center.playbackState = state == 1
                ? MPNowPlayingPlaybackStatePlaying : MPNowPlayingPlaybackStatePaused;
    }
}

JNIEXPORT void JNICALL Java_com_hvlplayer_MacMediaKeys_nativeStop(
        JNIEnv *env, jobject self) {
    @autoreleasepool {
        @synchronized ([MPRemoteCommandCenter class]) {
            MPRemoteCommandCenter *center = [MPRemoteCommandCenter sharedCommandCenter];
            [center.togglePlayPauseCommand removeTarget:toggleTarget];
            [center.playCommand removeTarget:playTarget];
            [center.pauseCommand removeTarget:pauseTarget];
            [center.previousTrackCommand removeTarget:previousTarget];
            [center.nextTrackCommand removeTarget:nextTarget];
            toggleTarget = nil;
            playTarget = nil;
            pauseTarget = nil;
            previousTarget = nil;
            nextTarget = nil;
            [MPNowPlayingInfoCenter defaultCenter].playbackState = MPNowPlayingPlaybackStateStopped;
            [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;
            if (receiver != NULL) {
                (*env)->DeleteGlobalRef(env, receiver);
                receiver = NULL;
            }
        }
    }
}
