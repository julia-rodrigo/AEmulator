#define _GNU_SOURCE
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <sys/mman.h>
typedef unsigned long ulong;
struct timespec_s { long tv_sec; long tv_nsec; };
#define EXPORT
static int cur_tid = 100;
static int errno_v; static int *__errno(void) { return &errno_v; }
static int fail(long r) { errno_v = (int)-r; return -1; }
static long fake_binder(int fd, void *arg);
static long sys3(long n, long a, long b, long c) {
    if (n == 54) return fake_binder((int)a, (void *)c);
    if (n == 224) return cur_tid;
    if (n == 199) return 10050;
    return 0;
}
#include "region.c"

/* ---------- fake binder ---------- */
static unsigned char *low(size_t n) { void *p = mmap(0, n, PROT_READ|PROT_WRITE, MAP_PRIVATE|MAP_ANONYMOUS|MAP_32BIT, -1, 0); return p; }
static unsigned char queue[4096]; static long qlen;     /* what the next read returns */
static unsigned writes[64][16]; static int nwrites;      /* commands seen by the "driver" (first words) */
static long last_read_size;
static void q_put(const void *d, long n) { memcpy(queue + qlen, d, n); qlen += n; }
static void q_u32(unsigned v) { q_put(&v, 4); }
static long fake_binder(int fd, void *arg) {
    struct bwr *b = arg;
    for (long p = b->write_consumed; p + 4 <= b->write_size;) {
        unsigned cmd = *(unsigned *)(b->write_buffer + p);
        unsigned sz = (cmd >> 16) & 0x3fff;
        if (nwrites < 64) { memset(writes[nwrites], 0, 64); memcpy(writes[nwrites], (void *)(b->write_buffer + p), 4 + sz > 64 ? 64 : 4 + sz); nwrites++; }
        p += 4 + sz;
    }
    b->write_consumed = b->write_size;
    last_read_size = b->read_size;
    if (b->read_size > b->read_consumed) {
        long room = b->read_size - b->read_consumed, n = 0;
        /* hand out whole commands that fit */
        while (n < qlen) {
            unsigned cmd = *(unsigned *)(queue + n); long len = 4 + ((cmd >> 16) & 0x3fff);
            if (n + len > room) break;
            n += len;
        }
        memcpy((void *)(b->read_buffer + b->read_consumed), queue, n);
        b->read_consumed += n;
        memmove(queue, queue + n, qlen - n); qlen -= n;
    }
    return 0;
}
static int fails;
#define CHECK(c, msg) do { if (c) printf("  ok   %s\n", msg); else { printf("  FAIL %s\n", msg); fails++; } } while (0)

/* parcel with an interface token, as writeInterfaceToken lays it out */
static unsigned char *parcel_iface(const char *name, unsigned *size) {
    unsigned char *d = low(4096); int n = strlen(name);
    *(unsigned *)d = 0x100; *(unsigned *)(d + 4) = n;
    for (int i = 0; i < n; i++) { d[8 + 2*i] = name[i]; d[9 + 2*i] = 0; }
    *size = 8 + 2 * (n + 1); return d;
}
static unsigned u(void *p) { return (unsigned)(ulong)p; }
static int do_ioctl(void *wb, long wsize, unsigned char *rb, long rsize, long *got) {
    struct bwr b = { wsize, 0, (ulong)wb, rsize, 0, (ulong)rb };
    int r = ioctl(3, BINDER_WRITE_READ_, &b);
    if (got) *got = b.read_consumed;
    return r;
}
/* libbinder-style sync call: BC_TRANSACTION to iface, reply carries a handle object */
static void sync_call_reply_handle(const char *iface, unsigned handle, int nested_first) {
    unsigned sz; unsigned char *d = parcel_iface(iface, &sz);
    unsigned w[11] = { BC_TRANSACTION_, 0 /* handle */, 0, 1 /* code */, 0 /* sync */, 0, 0, sz, 0, u(d), u(d) };
    unsigned char *rd = low(64); unsigned *ro = (unsigned *)low(16);
    *(unsigned *)rd = 0; *(unsigned *)(rd + 4) = 0;   /* session, status */
    unsigned *o = (unsigned *)(rd + 8); o[0] = T_HANDLE_; o[1] = 0x17f; o[2] = handle; o[3] = 0;
    ro[0] = 8;
    q_u32(BR_NOOP_); q_u32(BR_TRANSACTION_COMPLETE_);
    unsigned rep[11] = { BR_REPLY_, 0, 0, 0, 0, 0, 0, 24, 4, u(rd), u(ro) };
    q_put(rep, sizeof rep);
    unsigned char *rb = low(256); long got;
    do_ioctl(w, sizeof w, rb, 256, &got);
}
/* owner thread: an IAudioFlinger call from pid arrives, the reply hands out object (refs, obj) */
static void owner_serves_af(int tid, unsigned pid, void *refs, void *obj) {
    int save = cur_tid; cur_tid = tid;
    unsigned sz; unsigned char *d = parcel_iface("android.media.IAudioFlinger", &sz);
    unsigned in[11] = { BR_TRANSACTION_, 0x7000, 0x7004, 1, 0 /* sync */, pid, 10050, sz, 0, u(d), u(d) };
    qlen = 0; q_u32(BR_NOOP_); q_put(in, sizeof in);
    long got; do_ioctl(0, 0, low(256), 256, &got);
    unsigned char *rd = low(64); unsigned *ro = (unsigned *)low(16);
    unsigned *o = (unsigned *)(rd + 8); o[0] = T_BINDER_; o[1] = 0x17f; o[2] = u(refs); o[3] = u(obj); ro[0] = 8;
    unsigned br[11] = { BC_REPLY_, 0, 0, 0, 0, 0, 0, 24, 4, u(rd), u(ro) };
    qlen = 0; q_u32(BR_NOOP_); q_u32(BR_TRANSACTION_COMPLETE_);
    do_ioctl(br, sizeof br, low(64), 64, &got);
    cur_tid = save;
}
static int af_has(unsigned h) { for (int i = 0; i < AF_HANDLES; i++) if (g_af_handle[i] == h) return 1; return 0; }

int main(void) {
    printf("1. app: reply from IAudioFlinger carrying a track handle\n");
    cur_tid = 100;
    sync_call_reply_handle("android.media.IAudioFlinger", 7, 0);
    CHECK(af_has(7), "handle 7 remembered");
    printf("2. app: reply from another service is not remembered\n");
    sync_call_reply_handle("android.os.IServiceManager", 9, 0);
    CHECK(!af_has(9), "handle 9 (service manager) not remembered");
    int k = rt_find(100, 0);
    CHECK(k < 0 || (g_rt[k].depth == 0 && g_rt[k].af_level == 0), "call depth back to 0");

    printf("3. app: BC_RELEASE 7 via flushCommands (no read)\n");
    nwrites = 0; qlen = 0;
    q_u32(BR_NOOP_); q_u32(BR_TRANSACTION_COMPLETE_);   /* completion of the notice, available at once */
    unsigned rel[2] = { BC_RELEASE_, 7 };
    do_ioctl(rel, sizeof rel, 0, 0, 0);
    CHECK(nwrites == 2, "two writes reached binder (notice, then the release)");
    CHECK(writes[0][0] == BC_TRANSACTION_ && writes[0][1] == 7 && writes[0][3] == AEMU_RELEASE_CODE && (writes[0][4] & 1) && writes[0][7] == 4, "first: oneway notice to handle 7, 4 bytes");
    CHECK(*(unsigned *)(ulong)writes[0][9] == AEMU_RELEASE_MARK, "notice data is the mark");
    CHECK(last_read_size == 0, "libbinder's own write kept read_size 0");
    CHECK(writes[1][0] == BC_RELEASE_ && writes[1][1] == 7, "second: the BC_RELEASE itself");
    CHECK(qlen == 0, "the notice's completion was consumed");
    k = rt_find(100, 0);
    CHECK(k < 0 || (g_rt[k].swallow == 0 && g_rt[k].left_len == 0), "nothing left to swallow or hand back");
    CHECK(!af_has(7), "handle 7 forgotten");
    printf("4. app: BC_RELEASE 9 (not AudioFlinger's) sends no notice\n");
    nwrites = 0; unsigned rel9[2] = { BC_RELEASE_, 9 };
    do_ioctl(rel9, sizeof rel9, 0, 0, 0);
    CHECK(nwrites == 1 && writes[0][0] == BC_RELEASE_, "only the release");

    printf("5. app: completion arrives late; a stray BR_SPAWN_LOOPER and a BR_FINISHED-like short cmd\n");
    sync_call_reply_handle("android.media.IAudioFlinger", 21, 0);
    qlen = 0; nwrites = 0;
    q_u32(BR_NOOP_); q_u32(0x720e /* BR_FINISHED, short, not ours */);
    unsigned rel21[2] = { BC_RELEASE_, 21 };
    do_ioctl(rel21, sizeof rel21, 0, 0, 0);
    k = rt_find(100, 0);
    CHECK(k >= 0 && g_rt[k].swallow == 1, "completion still owed (swallow=1)");
    CHECK(k >= 0 && g_rt[k].left_len == 1 && g_rt[k].left[0] == 0x720e, "the stray command kept for libbinder");
    /* next libbinder read: our late completion, then its own oneway's completion */
    qlen = 0; q_u32(BR_NOOP_); q_u32(BR_TRANSACTION_COMPLETE_); q_u32(BR_TRANSACTION_COMPLETE_);
    unsigned char *rb = low(256); long got;
    unsigned ow[11] = { BC_TRANSACTION_, 3, 0, 5, 1 /* oneway */, 0, 0, 0, 0, 0, 0 };
    do_ioctl(ow, sizeof ow, rb, 256, &got);
    unsigned *r32 = (unsigned *)rb;
    CHECK(got == 12 && r32[0] == 0x720e && r32[1] == BR_NOOP_ && r32[2] == BR_TRANSACTION_COMPLETE_, "libbinder sees: stray cmd, NOOP, its one completion");
    k = rt_find(100, 0);
    CHECK(k < 0 || (g_rt[k].swallow == 0 && g_rt[k].left_len == 0), "all settled");

    printf("6. owner (mediaserver pool thread): the notice becomes BR_RELEASE + BR_DECREFS\n");
    cur_tid = 200; qlen = 0; nwrites = 0;
    int *refs = (int *)low(64); refs[0] = 5; refs[1] = 6;      /* weakref_impl: mStrong, mWeak */
    unsigned char *obj = low(64);
    owner_serves_af(202, 1234, refs, obj);
    cur_tid = 200; qlen = 0; nwrites = 0;
    static unsigned mark = AEMU_RELEASE_MARK;
    unsigned char *nbuf = low(16); *(unsigned *)nbuf = mark;
    unsigned tr[11] = { BR_TRANSACTION_, u(refs), u(obj), AEMU_RELEASE_CODE, 1, 1234, 10050, 4, 0, u(nbuf), u(nbuf) };
    q_u32(BR_NOOP_); q_put(tr, sizeof tr);
    rb = low(256);
    do_ioctl(0, 0, rb, 256, &got);
    r32 = (unsigned *)rb;
    CHECK(got == 48, "same length handed to libbinder");
    CHECK(r32[0] == BR_NOOP_ && r32[1] == BR_RELEASE_ && r32[2] == u(refs) && r32[3] == u(obj), "BR_RELEASE refs obj");
    CHECK(r32[4] == BR_DECREFS_ && r32[5] == u(refs) && r32[6] == u(obj), "BR_DECREFS refs obj");
    int noops = 1; for (int i = 7; i < 12; i++) noops &= r32[i] == BR_NOOP_;
    CHECK(noops, "rest is BR_NOOP");
    CHECK(nwrites == 1 && writes[0][0] == BC_FREE_BUFFER_ && writes[0][1] == u(nbuf), "notice buffer freed");

    printf("7. owner sends a new object at the same address: it gets its references\n");
    unsigned char *rd = low(64); unsigned *ro = (unsigned *)low(16);
    unsigned *o = (unsigned *)(rd + 8); o[0] = T_BINDER_; o[1] = 0x17f; o[2] = u(refs); o[3] = u(obj); ro[0] = 8;
    unsigned br[11] = { BC_REPLY_, 0, 0, 0, 0, 0, 0, 24, 4, u(rd), u(ro) };
    qlen = 0; q_u32(BR_NOOP_); q_u32(BR_TRANSACTION_COMPLETE_);
    do_ioctl(br, sizeof br, low(64), 64, &got);
    CHECK(refs[0] == 6 && refs[1] == 7, "mStrong and mWeak +1");
    qlen = 0; q_u32(BR_NOOP_); q_u32(BR_TRANSACTION_COMPLETE_);
    do_ioctl(br, sizeof br, low(64), 64, &got);
    CHECK(refs[0] == 6 && refs[1] == 7, "only once");
    printf("   same address, other cookie (binderd makes a new node itself): untouched\n");
    /* scenario 7's reply above went out as a plain BC_REPLY (no AF call): hand it out through AF, then release */
    owner_serves_af(202, 1234, refs, obj);
    qlen = 0; q_u32(BR_NOOP_); q_put(tr, sizeof tr);
    do_ioctl(0, 0, low(256), 256, &got);
    o[3] = u(obj) + 16;
    qlen = 0; q_u32(BR_NOOP_); q_u32(BR_TRANSACTION_COMPLETE_);
    do_ioctl(br, sizeof br, low(64), 64, &got);
    CHECK(refs[0] == 6 && refs[1] == 7, "no extra references");

    printf("8. owner thread waiting for a reply gets the notice: it goes to the pool\n");
    owner_serves_af(203, 1234, refs, obj);
    cur_tid = 300; qlen = 0; nwrites = 0;
    unsigned sz; unsigned char *d = parcel_iface("android.os.IFoo", &sz);
    unsigned call[11] = { BC_TRANSACTION_, 4, 0, 1, 0, 0, 0, sz, 0, u(d), u(d) };
    q_u32(BR_NOOP_); q_u32(BR_TRANSACTION_COMPLETE_); q_put(tr, sizeof tr);
    rb = low(256);
    do_ioctl(call, sizeof call, rb, 256, &got);
    r32 = (unsigned *)rb;
    CHECK(got == 8 && r32[1] == BR_TRANSACTION_COMPLETE_, "waiting thread does not see it");
    CHECK(g_held_len == 24, "24 bytes of releases held for the pool");
    cur_tid = 201; qlen = 0; q_u32(BR_NOOP_);
    rb = low(256);
    do_ioctl(0, 0, rb, 256, &got);
    r32 = (unsigned *)rb;
    CHECK(got == 28 && r32[1] == BR_RELEASE_ && r32[4] == BR_DECREFS_, "pool thread gets them");

    printf("9. app: while waiting for AudioFlinger, handles an incoming call that calls another service\n");
    cur_tid = 400; qlen = 0;
    unsigned char *afd = parcel_iface("android.media.IAudioFlinger", &sz);
    unsigned afcall[11] = { BC_TRANSACTION_, 0, 0, 1, 0, 0, 0, sz, 0, u(afd), u(afd) };
    /* the AF call goes out; an incoming sync transaction arrives in the same read */
    unsigned char *ind = low(64);
    unsigned intr[11] = { BR_TRANSACTION_, 0x1000, 0x2000, 3, 0 /* sync */, 55, 10000, 8, 0, u(ind), u(ind) };
    q_u32(BR_NOOP_); q_u32(BR_TRANSACTION_COMPLETE_); q_put(intr, sizeof intr);
    do_ioctl(afcall, sizeof afcall, low(256), 256, &got);
    CHECK(got == 52, "incoming transaction handed to libbinder");
    /* the handler calls IOther; its reply carries handle 31 */
    sync_call_reply_handle("android.os.IOther", 31, 0);
    CHECK(!af_has(31), "nested reply's handle 31 not taken for AudioFlinger's");
    /* the handler replies; then AudioFlinger's reply with handle 32 */
    unsigned char *rd2 = low(64); unsigned *ro2 = (unsigned *)low(16);
    unsigned *o2 = (unsigned *)(rd2 + 8); o2[0] = T_HANDLE_; o2[2] = 32; ro2[0] = 8;
    unsigned rep2[11] = { BR_REPLY_, 0, 0, 0, 0, 0, 0, 24, 4, u(rd2), u(ro2) };
    unsigned bcrep[11] = { BC_REPLY_, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    qlen = 0; q_u32(BR_NOOP_); q_u32(BR_TRANSACTION_COMPLETE_); q_put(rep2, sizeof rep2);
    rb = low(256);
    do_ioctl(bcrep, sizeof bcrep, rb, 256, &got);
    while (qlen) do_ioctl(0, 0, rb, 256, &got);
    CHECK(af_has(32), "AudioFlinger's reply handle 32 taken");
    k = rt_find(400, 0);
    CHECK(k < 0, "thread record freed");

    printf("10. owner: notices that are not its to act on are dropped\n");
    {
        int *r2 = (int *)low(64); r2[0] = 3; r2[1] = 4; unsigned char *o2 = low(64);
        owner_serves_af(204, 5555, r2, o2);
        unsigned t2[11] = { BR_TRANSACTION_, u(r2), u(o2), AEMU_RELEASE_CODE, 1, 6666 /* other pid */, 10050, 4, 0, u(nbuf), u(nbuf) };
        cur_tid = 205; qlen = 0; nwrites = 0; q_u32(BR_NOOP_); q_put(t2, sizeof t2);
        rb = low(256); do_ioctl(0, 0, rb, 256, &got); r32 = (unsigned *)rb;
        int noop = 1; for (int i = 0; i < 12; i++) noop &= r32[i] == BR_NOOP_;
        CHECK(got == 48 && noop, "from another pid: only BR_NOOPs reach libbinder");
        CHECK(nwrites == 1 && writes[0][0] == BC_FREE_BUFFER_, "its buffer is still freed");
        t2[5] = 5555;
        qlen = 0; q_u32(BR_NOOP_); q_put(t2, sizeof t2);
        rb = low(256); do_ioctl(0, 0, rb, 256, &got); r32 = (unsigned *)rb;
        CHECK(r32[1] == BR_RELEASE_ && r32[2] == u(r2), "from the right pid: released");
        qlen = 0; q_u32(BR_NOOP_); q_put(t2, sizeof t2);
        rb = low(256); do_ioctl(0, 0, rb, 256, &got); r32 = (unsigned *)rb;
        noop = 1; for (int i = 0; i < 12; i++) noop &= r32[i] == BR_NOOP_;
        CHECK(noop, "a second notice for it: dropped");
        int *r3 = (int *)low(64); unsigned char *o3 = low(64);
        unsigned t3[11] = { BR_TRANSACTION_, u(r3), u(o3), AEMU_RELEASE_CODE, 1, 5555, 10050, 4, 0, u(nbuf), u(nbuf) };
        qlen = 0; q_u32(BR_NOOP_); q_put(t3, sizeof t3);
        rb = low(256); do_ioctl(0, 0, rb, 256, &got); r32 = (unsigned *)rb;
        noop = 1; for (int i = 0; i < 12; i++) noop &= r32[i] == BR_NOOP_;
        CHECK(noop, "for an object never handed out: dropped");
    }

    printf("\n%s (%d failures)\n", fails ? "FAILED" : "ALL PASSED", fails);
    return fails != 0;
}
