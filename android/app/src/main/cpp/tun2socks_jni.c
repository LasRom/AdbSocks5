#include <errno.h>
#include <jni.h>
#include <string.h>
#include <unistd.h>

int hev_socks5_tunnel_main_from_str(const unsigned char *config_str,
                                    unsigned int config_len, int tun_fd);
void hev_socks5_tunnel_quit(void);

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

    int tunnel_fd = dup(tun_fd);
    if (tunnel_fd < 0) {
        int error = errno;
        (*env)->ReleaseStringUTFChars(env, config, config_chars);
        return -error;
    }

    int result = hev_socks5_tunnel_main_from_str(
        (const unsigned char *) config_chars,
        (unsigned int) strlen(config_chars),
        tunnel_fd
    );

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
