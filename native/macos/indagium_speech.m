#import <Foundation/Foundation.h>
#import <Speech/Speech.h>
#import <AVFoundation/AVFoundation.h>
#import <jni.h>

static NSString *jsonResult(NSString *status, NSString *text, NSString *message) {
    NSMutableDictionary *value = [@{ @"status": status ?: @"failure" } mutableCopy];
    if (text != nil) value[@"text"] = text;
    if (message != nil) value[@"message"] = message;
    NSError *error = nil;
    NSData *data = [NSJSONSerialization dataWithJSONObject:value options:0 error:&error];
    if (error != nil || data == nil) return @"{\"status\":\"failure\",\"message\":\"Could not encode Apple Speech result.\"}";
    return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
}

static jstring javaString(JNIEnv *env, NSString *value) {
    return (*env)->NewStringUTF(env, (value ?: @"").UTF8String);
}

// A `desktopRun`/IntelliJ launch runs the bare `java` binary, which has no Info.plist at all —
// jpackage is what stamps NSSpeechRecognitionUsageDescription in, and only for a packaged bundle
// (see build.gradle.kts's macOS infoPlist.extraKeysRawXml). Touching SFSpeechRecognizer without
// that key present has macOS's TCC hard-kill the process (Namespace TCC, Code 0) instead of
// raising a normal error, so this must be checked before *any* Speech framework call, not just
// before the obvious `SFSpeechRecognizer` construction.
static BOOL hasSpeechUsageDescription(void) {
    id description = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"NSSpeechRecognitionUsageDescription"];
    return [description isKindOfClass:[NSString class]] && [(NSString *)description length] > 0;
}

static NSString *const kMissingUsageDescriptionMessage =
    @"Apple Speech needs a packaged Indagium build. A development run has no Info.plist usage "
    @"description, and macOS kills any process that asks without one. Choose Whisper instead, or "
    @"run a packaged build.";

static NSString *availabilityMessage(NSString *localeIdentifier) {
    if (!hasSpeechUsageDescription()) return kMissingUsageDescriptionMessage;
    SFSpeechRecognizer *recognizer = [[SFSpeechRecognizer alloc] initWithLocale:[[NSLocale alloc] initWithLocaleIdentifier:localeIdentifier]];
    if (recognizer == nil) return @"Apple Speech does not recognize this language on this Mac. Choose Whisper instead.";
    if (![recognizer supportsOnDeviceRecognition]) return @"Apple does not have an on-device speech model for this language. Choose Whisper instead.";
    switch ([SFSpeechRecognizer authorizationStatus]) {
        case SFSpeechRecognizerAuthorizationStatusAuthorized: return nil;
        case SFSpeechRecognizerAuthorizationStatusDenied: return @"Speech Recognition permission is denied for Indagium. Enable it in macOS Privacy & Security.";
        case SFSpeechRecognizerAuthorizationStatusRestricted: return @"Speech Recognition is restricted by this Mac's policy.";
        case SFSpeechRecognizerAuthorizationStatusNotDetermined: return @"Speech Recognition permission is required.";
    }
    return @"Apple Speech is unavailable.";
}

JNIEXPORT jboolean JNICALL Java_com_indagium_voice_AppleSpeechNative_nativeEnsureReady
  (JNIEnv *env, jclass clazz, jstring language) {
    if (!hasSpeechUsageDescription()) return JNI_FALSE;
    const char *chars = (*env)->GetStringUTFChars(env, language, NULL);
    NSString *locale = [NSString stringWithUTF8String:chars];
    (*env)->ReleaseStringUTFChars(env, language, chars);
    NSString *before = availabilityMessage(locale);
    if (before == nil) return JNI_TRUE;
    if ([SFSpeechRecognizer authorizationStatus] != SFSpeechRecognizerAuthorizationStatusNotDetermined) return JNI_FALSE;
    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
    __block SFSpeechRecognizerAuthorizationStatus status = SFSpeechRecognizerAuthorizationStatusNotDetermined;
    [SFSpeechRecognizer requestAuthorization:^(SFSpeechRecognizerAuthorizationStatus result) {
        status = result;
        dispatch_semaphore_signal(semaphore);
    }];
    dispatch_semaphore_wait(semaphore, dispatch_time(DISPATCH_TIME_NOW, 30 * NSEC_PER_SEC));
    return status == SFSpeechRecognizerAuthorizationStatusAuthorized && availabilityMessage(locale) == nil ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_indagium_voice_AppleSpeechNative_nativeAvailabilityMessage
  (JNIEnv *env, jclass clazz, jstring language) {
    if (!hasSpeechUsageDescription()) return javaString(env, kMissingUsageDescriptionMessage);
    const char *chars = (*env)->GetStringUTFChars(env, language, NULL);
    NSString *locale = [NSString stringWithUTF8String:chars];
    (*env)->ReleaseStringUTFChars(env, language, chars);
    return javaString(env, availabilityMessage(locale) ?: @"Apple Speech is ready for on-device recognition.");
}

JNIEXPORT jstring JNICALL Java_com_indagium_voice_AppleSpeechNative_nativeTranscribe
  (JNIEnv *env, jclass clazz, jbyteArray pcm, jstring language) {
    if (!hasSpeechUsageDescription()) return javaString(env, jsonResult(@"failure", nil, kMissingUsageDescriptionMessage));
    const char *chars = (*env)->GetStringUTFChars(env, language, NULL);
    NSString *locale = [NSString stringWithUTF8String:chars];
    (*env)->ReleaseStringUTFChars(env, language, chars);
    NSString *unavailable = availabilityMessage(locale);
    if (unavailable != nil) return javaString(env, jsonResult(@"failure", nil, unavailable));

    SFSpeechRecognizer *recognizer = [[SFSpeechRecognizer alloc] initWithLocale:[[NSLocale alloc] initWithLocaleIdentifier:locale]];
    SFSpeechAudioBufferRecognitionRequest *request = [SFSpeechAudioBufferRecognitionRequest new];
    request.requiresOnDeviceRecognition = YES;
    request.shouldReportPartialResults = NO;
    request.taskHint = SFSpeechRecognitionTaskHintDictation;

    jsize length = (*env)->GetArrayLength(env, pcm);
    AVAudioFormat *format = [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatInt16 sampleRate:16000 channels:1 interleaved:YES];
    AVAudioPCMBuffer *buffer = [[AVAudioPCMBuffer alloc] initWithPCMFormat:format frameCapacity:(AVAudioFrameCount)(length / 2)];
    buffer.frameLength = (AVAudioFrameCount)(length / 2);
    jbyte *bytes = (*env)->GetByteArrayElements(env, pcm, NULL);
    memcpy(buffer.int16ChannelData[0], bytes, (size_t)length);
    (*env)->ReleaseByteArrayElements(env, pcm, bytes, JNI_ABORT);
    [request appendAudioPCMBuffer:buffer];
    [request endAudio];

    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
    __block NSString *text = nil;
    __block NSString *failure = nil;
    __block BOOL finished = NO;
    SFSpeechRecognitionTask *task = [recognizer recognitionTaskWithRequest:request resultHandler:^(SFSpeechRecognitionResult *result, NSError *error) {
        if (result != nil && result.isFinal) { text = result.bestTranscription.formattedString; finished = YES; dispatch_semaphore_signal(semaphore); }
        if (error != nil && !finished) { failure = error.localizedDescription; finished = YES; dispatch_semaphore_signal(semaphore); }
    }];
    long wait = dispatch_semaphore_wait(semaphore, dispatch_time(DISPATCH_TIME_NOW, 65 * NSEC_PER_SEC));
    if (wait != 0) { [task cancel]; return javaString(env, jsonResult(@"failure", nil, @"Apple Speech timed out. Try a shorter recording.")); }
    if (failure != nil) return javaString(env, jsonResult(@"failure", nil, failure));
    return javaString(env, jsonResult(@"success", text ?: @"", nil));
}
