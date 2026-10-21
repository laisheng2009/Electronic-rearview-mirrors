#ifndef BYD_EXTEND_CAMERA_TUNNEL_JNI_BRIDGE_H
#define BYD_EXTEND_CAMERA_TUNNEL_JNI_BRIDGE_H

#include <stddef.h>

int camera_secret_get(const char *key, char **out_value, size_t *out_length);
int camera_secret_put(const char *key, const char *value, size_t length);
int camera_secret_remove(const char *key);
int camera_secret_remove_prefix(const char *prefix);
void camera_secret_clear(void *value, size_t length);
int camera_tunnel_set_runtime_dir(char *path);
void camera_tunnel_network_changed(void);
void camera_tunnel_log_panic(const char *operation, const char *stage, const char *location, const char *detail);

#endif
