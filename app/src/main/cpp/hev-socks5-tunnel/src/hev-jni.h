/*
 ============================================================================
 Name        : hev-jni.h
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2019 - 2023 hev
 Description : Java Native Interface
 ============================================================================
 */

#ifndef __HEV_JNI_H__
#define __HEV_JNI_H__

#include <stddef.h>

int hev_jni_dns_resolve (const unsigned char *query, size_t length);

#endif /* __HEV_JNI_H__ */
