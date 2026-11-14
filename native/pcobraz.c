// PlayCaption – mezivrstva mezi Chiaki (libchiaki-jni) a systémovým dekodérem (libmediandk).
// Telefon: obraz z PS5 se dekóduje rovnou do paměti (bez grafického čipu) a předá se aplikaci
// jako YUV (I420) – stejně jako u Xboxu. Když je vypnuto, jen všechno přeposílá dál beze změny.
// Licence: AGPLv3 (součást PlayCaption).
#include <stddef.h>
#include <stdint.h>
#include <jni.h>

typedef struct AMediaCodec AMediaCodec;
typedef struct AMediaFormat AMediaFormat;
typedef struct AMediaCrypto AMediaCrypto;
typedef struct ANativeWindow ANativeWindow;
typedef int media_status_t;
typedef struct { int32_t offset; int32_t size; int64_t presentationTimeUs; uint32_t flags; } AMediaCodecBufferInfo;
typedef long ssize_t_;

void *dlopen(const char *, int);
void *dlsym(void *, const char *);
void *malloc(size_t);
void free(void *);
void *memcpy(void *, const void *, size_t);
int strncmp(const char *, const char *, size_t);
int pthread_key_create(unsigned int *, void (*)(void *));
void *pthread_getspecific(unsigned int);
int pthread_setspecific(unsigned int, const void *);
int __android_log_print(int, const char *, const char *, ...);

#ifdef __LP64__
#define RTLD_NOW 2
#else
#define RTLD_NOW 0
#endif
#define TAG "PlayCaptionObraz"

// ---- skutečné funkce ze systému ----
static void *knih;
static AMediaCodec *(*r_createDecoderByType)(const char *);
static media_status_t (*r_configure)(AMediaCodec *, const AMediaFormat *, ANativeWindow *, AMediaCrypto *, uint32_t);
static media_status_t (*r_delete)(AMediaCodec *);
static ssize_t_ (*r_dequeueInputBuffer)(AMediaCodec *, int64_t);
static ssize_t_ (*r_dequeueOutputBuffer)(AMediaCodec *, AMediaCodecBufferInfo *, int64_t);
static uint8_t *(*r_getInputBuffer)(AMediaCodec *, size_t, size_t *);
static uint8_t *(*r_getOutputBuffer)(AMediaCodec *, size_t, size_t *);
static media_status_t (*r_queueInputBuffer)(AMediaCodec *, size_t, long, size_t, uint64_t, uint32_t);
static media_status_t (*r_releaseOutputBuffer)(AMediaCodec *, size_t, int);
static media_status_t (*r_start)(AMediaCodec *);
static media_status_t (*r_stop)(AMediaCodec *);
static AMediaFormat *(*r_getOutputFormat)(AMediaCodec *);
static media_status_t (*r_formatDelete)(AMediaFormat *);
static AMediaFormat *(*r_formatNew)(void);
static void (*r_setInt32)(AMediaFormat *, const char *, int32_t);
static void (*r_setString)(AMediaFormat *, const char *, const char *);
static int (*r_getInt32)(AMediaFormat *, const char *, int32_t *);

const char *AMEDIAFORMAT_KEY_MIME = "mime";
const char *AMEDIAFORMAT_KEY_WIDTH = "width";
const char *AMEDIAFORMAT_KEY_HEIGHT = "height";
const char *AMEDIAFORMAT_KEY_SAMPLE_RATE = "sample-rate";
const char *AMEDIAFORMAT_KEY_CHANNEL_COUNT = "channel-count";

#define NACTI(f, jmeno) f = dlsym(knih, jmeno)
static void nactiKlic(const char **cil, const char *jmeno) {
    const char **p = (const char **)dlsym(knih, jmeno);
    if (p && *p) *cil = *p;
}

__attribute__((constructor)) static void priprav(void) {
    knih = dlopen("libmediandk.so", RTLD_NOW);
    if (!knih) return;
    NACTI(r_createDecoderByType, "AMediaCodec_createDecoderByType");
    NACTI(r_configure, "AMediaCodec_configure");
    NACTI(r_delete, "AMediaCodec_delete");
    NACTI(r_dequeueInputBuffer, "AMediaCodec_dequeueInputBuffer");
    NACTI(r_dequeueOutputBuffer, "AMediaCodec_dequeueOutputBuffer");
    NACTI(r_getInputBuffer, "AMediaCodec_getInputBuffer");
    NACTI(r_getOutputBuffer, "AMediaCodec_getOutputBuffer");
    NACTI(r_queueInputBuffer, "AMediaCodec_queueInputBuffer");
    NACTI(r_releaseOutputBuffer, "AMediaCodec_releaseOutputBuffer");
    NACTI(r_start, "AMediaCodec_start");
    NACTI(r_stop, "AMediaCodec_stop");
    NACTI(r_getOutputFormat, "AMediaCodec_getOutputFormat");
    NACTI(r_formatDelete, "AMediaFormat_delete");
    NACTI(r_formatNew, "AMediaFormat_new");
    NACTI(r_setInt32, "AMediaFormat_setInt32");
    NACTI(r_setString, "AMediaFormat_setString");
    NACTI(r_getInt32, "AMediaFormat_getInt32");
    nactiKlic(&AMEDIAFORMAT_KEY_MIME, "AMEDIAFORMAT_KEY_MIME");
    nactiKlic(&AMEDIAFORMAT_KEY_WIDTH, "AMEDIAFORMAT_KEY_WIDTH");
    nactiKlic(&AMEDIAFORMAT_KEY_HEIGHT, "AMEDIAFORMAT_KEY_HEIGHT");
    nactiKlic(&AMEDIAFORMAT_KEY_SAMPLE_RATE, "AMEDIAFORMAT_KEY_SAMPLE_RATE");
    nactiKlic(&AMEDIAFORMAT_KEY_CHANNEL_COUNT, "AMEDIAFORMAT_KEY_CHANNEL_COUNT");
}

// ---- stav ----
static volatile int zapnuto = 0;          // aplikace chce obraz do paměti
static AMediaCodec *video = 0;            // dekodér obrazu (ne zvuku)
static volatile int vPameti = 0;          // dekodér obrazu běží bez plochy (do paměti)
static int sirka = 0, vyska = 0, krok = 0, radku = 0, barvy = 0;
static int maxSirka = 0, maxVyska = 0;
static uint8_t *pomU = 0, *pomV = 0;
static int pomVel = 0;

static JavaVM *jvm = 0;
static jclass trida = 0;
static jmethodID metoda = 0;
static unsigned int klicVlakna = 0;
static int klicHotov = 0;

static void odpoj(void *x) { (void)x; if (jvm) (*jvm)->DetachCurrentThread(jvm); }

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *r) { (void)r; jvm = vm; return JNI_VERSION_1_6; }

// cz.titulky.tv.PametObraz.zapni(ano, maxSirka, maxVyska)
JNIEXPORT void JNICALL Java_cz_titulky_tv_PametObraz_zapni(JNIEnv *env, jclass cls, jboolean ano, jint mw, jint mh) {
    if (!trida) {
        trida = (*env)->NewGlobalRef(env, cls);
        metoda = (*env)->GetStaticMethodID(env, cls, "snimek",
            "(IILjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;ILjava/nio/ByteBuffer;I)V");
        if (!metoda) { (*env)->ExceptionClear(env); trida = 0; return; }
    }
    if (!klicHotov) { pthread_key_create(&klicVlakna, odpoj); klicHotov = 1; }
    maxSirka = mw; maxVyska = mh;
    zapnuto = ano ? 1 : 0;
}

// 1 = dekodér teď dává obraz do paměti, 0 = přes plochu (grafiku)
JNIEXPORT jint JNICALL Java_cz_titulky_tv_PametObraz_stav(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; return vPameti;
}
JNIEXPORT jint JNICALL Java_cz_titulky_tv_PametObraz_barvy(JNIEnv *env, jclass cls) {
    (void)env; (void)cls; return barvy;
}

static void nactiFormat(AMediaCodec *c) {
    if (!r_getOutputFormat || !r_getInt32) return;
    AMediaFormat *f = r_getOutputFormat(c);
    if (!f) return;
    int32_t v;
    if (r_getInt32(f, "width", &v)) sirka = v;
    if (r_getInt32(f, "height", &v)) vyska = v;
    krok = sirka; radku = vyska;
    if (r_getInt32(f, "stride", &v) && v >= sirka) krok = v;
    if (r_getInt32(f, "slice-height", &v) && v >= vyska) radku = v;
    if (r_getInt32(f, "color-format", &v)) barvy = v;
    if (r_formatDelete) r_formatDelete(f);
}

static JNIEnv *prostredi(void) {
    if (!jvm) return 0;
    JNIEnv *env = 0;
    if ((*jvm)->GetEnv(jvm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*jvm)->AttachCurrentThreadAsDaemon(jvm, (void **)&env, 0) != JNI_OK) return 0;
    if (klicHotov) pthread_setspecific(klicVlakna, (void *)1);
    return env;
}

static void predej(uint8_t *data, size_t vel) {
    if (!trida || !metoda || !data) return;
    int w = sirka, h = vyska;
    if (maxSirka > 0 && w > maxSirka) w = maxSirka;
    if (maxVyska > 0 && h > maxVyska) h = maxVyska;
    w &= ~1; h &= ~1;
    if (w < 16 || h < 16 || krok < w) return;
    int rk = radku;
    // obrazová data musí mít místo pro Y + UV
    if ((size_t)krok * rk + (size_t)krok * (h >> 1) > vel) {
        rk = h; // některé dekodéry neuvádějí výšku řádků → bez zarovnání
        if ((size_t)krok * rk + (size_t)krok * (h >> 1) > vel) return;
    }
    int cw = w >> 1, ch = h >> 1;
    JNIEnv *env = prostredi();
    if (!env) return;
    jobject by = (*env)->NewDirectByteBuffer(env, data, (jlong)krok * h);
    jobject bu, bv;
    int su, sv;
    uint8_t *uv = data + (size_t)krok * rk;
    if (barvy == 19 /* YUV420Planar (I420) */) {
        int kc = krok >> 1;
        uint8_t *v = uv + (size_t)kc * (rk >> 1);
        bu = (*env)->NewDirectByteBuffer(env, uv, (jlong)kc * ch);
        bv = (*env)->NewDirectByteBuffer(env, v, (jlong)kc * ch);
        su = kc; sv = kc;
    } else {
        // NV12 (UVUV…) – rozdělit na U a V
        int pot = cw * ch;
        if (pot > pomVel) {
            if (pomU) free(pomU);
            if (pomV) free(pomV);
            pomU = malloc(pot); pomV = malloc(pot);
            pomVel = (pomU && pomV) ? pot : 0;
            if (!pomVel) return;
        }
        for (int r = 0; r < ch; r++) {
            uint8_t *z = uv + (size_t)r * krok;
            uint8_t *cu = pomU + r * cw, *cv = pomV + r * cw;
            for (int i = 0; i < cw; i++) { cu[i] = z[2 * i]; cv[i] = z[2 * i + 1]; }
        }
        bu = (*env)->NewDirectByteBuffer(env, pomU, (jlong)pot);
        bv = (*env)->NewDirectByteBuffer(env, pomV, (jlong)pot);
        su = cw; sv = cw;
    }
    if (by && bu && bv)
        (*env)->CallStaticVoidMethod(env, trida, metoda, w, h, by, krok, bu, su, bv, sv);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (by) (*env)->DeleteLocalRef(env, by);
    if (bu) (*env)->DeleteLocalRef(env, bu);
    if (bv) (*env)->DeleteLocalRef(env, bv);
}

// ---- přeposílané funkce ----
AMediaCodec *AMediaCodec_createDecoderByType(const char *mime) {
    if (!knih) priprav();
    AMediaCodec *c = r_createDecoderByType ? r_createDecoderByType(mime) : 0;
    if (c && mime && strncmp(mime, "video/", 6) == 0) video = c;
    return c;
}

media_status_t AMediaCodec_configure(AMediaCodec *c, const AMediaFormat *f, ANativeWindow *okno, AMediaCrypto *cr, uint32_t fl) {
    if (c == video) {
        vPameti = 0;
        sirka = vyska = krok = radku = barvy = 0;
        if (zapnuto && trida && okno) {
            media_status_t r = r_configure(c, f, 0, cr, fl);
            if (r == 0) {
                vPameti = 1;
                __android_log_print(4, TAG, "dekodér obrazu do paměti");
                return r;
            }
            __android_log_print(5, TAG, "do paměti nejde (%d) – přes plochu", r);
        }
    }
    return r_configure(c, f, okno, cr, fl);
}

media_status_t AMediaCodec_delete(AMediaCodec *c) {
    if (c == video) { video = 0; vPameti = 0; }
    return r_delete(c);
}

ssize_t_ AMediaCodec_dequeueOutputBuffer(AMediaCodec *c, AMediaCodecBufferInfo *info, int64_t t) {
    ssize_t_ r = r_dequeueOutputBuffer(c, info, t);
    if (c == video && vPameti && (r == -2 /* změna formátu */ || (r >= 0 && sirka == 0))) nactiFormat(c);
    return r;
}

media_status_t AMediaCodec_releaseOutputBuffer(AMediaCodec *c, size_t i, int render) {
    if (c == video && vPameti) {
        if (render && zapnuto && r_getOutputBuffer) {
            size_t vel = 0;
            uint8_t *data = r_getOutputBuffer(c, i, &vel);
            if (data && vel > 0) predej(data, vel);
        }
        return r_releaseOutputBuffer(c, i, 0);
    }
    return r_releaseOutputBuffer(c, i, render);
}

ssize_t_ AMediaCodec_dequeueInputBuffer(AMediaCodec *c, int64_t t) { return r_dequeueInputBuffer(c, t); }
uint8_t *AMediaCodec_getInputBuffer(AMediaCodec *c, size_t i, size_t *s) { return r_getInputBuffer(c, i, s); }
uint8_t *AMediaCodec_getOutputBuffer(AMediaCodec *c, size_t i, size_t *s) { return r_getOutputBuffer(c, i, s); }
media_status_t AMediaCodec_queueInputBuffer(AMediaCodec *c, size_t i, long o, size_t s, uint64_t t, uint32_t f) { return r_queueInputBuffer(c, i, o, s, t, f); }
media_status_t AMediaCodec_start(AMediaCodec *c) { return r_start(c); }
media_status_t AMediaCodec_stop(AMediaCodec *c) { return r_stop(c); }
media_status_t AMediaFormat_delete(AMediaFormat *f) { return r_formatDelete(f); }
AMediaFormat *AMediaFormat_new(void) { if (!knih) priprav(); return r_formatNew(); }
void AMediaFormat_setInt32(AMediaFormat *f, const char *k, int32_t v) { r_setInt32(f, k, v); }
void AMediaFormat_setString(AMediaFormat *f, const char *k, const char *v) { r_setString(f, k, v); }
