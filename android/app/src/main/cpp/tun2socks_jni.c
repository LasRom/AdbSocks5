#include <errno.h>
#include <jni.h>
#include <string.h>
#include <unistd.h>

int hev_socks5_tunnel_main_from_str(const unsigned char *config_str,
                                    unsigned int config_len, int tun_fd);
void hev_socks5_tunnel_quit(void);
void hev_socks5_set_protect_socket(int (*callback)(int fd));

static JavaVM *global_vm = NULL;
static jobject protect_object = NULL;
static jmethodID protect_method = NULL;

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    global_vm = vm;
    return JNI_VERSION_1_6;
}

static int protect_socket_callback(int fd) {
    if (global_vm == NULL || protect_object == NULL || protect_method == NULL) {
        return -1;
    }

    JNIEnv *env = NULL;
    int attached = 0;
    if ((*global_vm)->GetEnv(global_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*global_vm)->AttachCurrentThread(global_vm, &env, NULL) != JNI_OK) {
            return -1;
        }
        attached = 1;
    }

    jboolean protected = (*env)->CallBooleanMethod(
        env,
        protect_object,
        protect_method,
        (jint) fd
    );
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        protected = JNI_FALSE;
    }

    if (attached) {
        (*global_vm)->DetachCurrentThread(global_vm);
    }

    return protected == JNI_TRUE ? 0 : -1;
}

JNIEXPORT jint JNICALL
Java_com_proxy_Tun2Socks_nativeStart(JNIEnv *env, jobject thiz, jstring config,
                                     jint tun_fd) {
    (void) thiz;

    if (config == NULL || tun_fd < 0) {
        return -EINVAL;
    }

    const char *config_chars = (*env)->GetStringUTFChars(env, config, NULL);
    if (config_chars == NULL) {
        return -ENOMEM;
    }

    jobject local_protect_object = (*env)->NewGlobalRef(env, thiz);
    if (local_protect_object == NULL) {
        (*env)->ReleaseStringUTFChars(env, config, config_chars);
        return -ENOMEM;
    }

    jclass protect_class = (*env)->GetObjectClass(env, thiz);
    jmethodID local_protect_method = (*env)->GetMethodID(env, protect_class, "protectSocket", "(I)Z");
    (*env)->DeleteLocalRef(env, protect_class);
    if (local_protect_method == NULL) {
        (*env)->DeleteGlobalRef(env, local_protect_object);
        (*env)->ReleaseStringUTFChars(env, config, config_chars);
        return -EINVAL;
    }

    int tunnel_fd = dup(tun_fd);
    if (tunnel_fd < 0) {
        int error = errno;
        (*env)->DeleteGlobalRef(env, local_protect_object);
        (*env)->ReleaseStringUTFChars(env, config, config_chars);
        return -error;
    }

    protect_object = local_protect_object;
    protect_method = local_protect_method;
    hev_socks5_set_protect_socket(protect_socket_callback);

    int result = hev_socks5_tunnel_main_from_str(
        (const unsigned char *) config_chars,
        (unsigned int) strlen(config_chars),
        tunnel_fd
    );

    hev_socks5_set_protect_socket(NULL);
    protect_method = NULL;
    protect_object = NULL;
    (*env)->DeleteGlobalRef(env, local_protect_object);

    close(tunnel_fd);
    (*env)->ReleaseStringUTFChars(env, config, config_chars);
    return result;
}

JNIEXPORT void JNICALL
Java_com_proxy_Tun2Socks_nativeStop(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    hev_socks5_tunnel_quit();
}
