/*
 * pty.c: The pseudo-terminal the app's terminal runs on
 *
 * Android has no forkpty() an app can call, so this does what it does: open a
 * master, fork, make the slave the child's controlling terminal and exec. It
 * is written here rather than taken from a terminal emulator project, as the
 * one most people would reach for is GPLv3 and this project is MIT.
 *
 * Every JNI call happens before fork(). Between fork() and exec() the child
 * only calls async-signal-safe functions, since the parent's other threads,
 * and whatever locks they held, do not exist there.
 */

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

extern char **environ;

/* Copies a java String[] into a NULL-terminated C array, or returns NULL */
static char **to_c_array(JNIEnv *env, jobjectArray array)
{
    if (array == NULL)
        return NULL;

    jsize count = (*env)->GetArrayLength(env, array);
    char **result = calloc((size_t) count + 1, sizeof(char *));
    if (result == NULL)
        return NULL;

    for (jsize i = 0; i < count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *chars = (*env)->GetStringUTFChars(env, item, NULL);
        result[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, item, chars);
        (*env)->DeleteLocalRef(env, item);
    }

    return result;
}

static void free_c_array(char **array)
{
    if (array == NULL)
        return;
    for (char **item = array; *item != NULL; item++)
        free(*item);
    free(array);
}

static void throw_io(JNIEnv *env, const char *message)
{
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls != NULL)
        (*env)->ThrowNew(env, cls, message);
}

/*
 * Starts argv[0] (looked up on PATH) on a new pty
 * Returns the master fd, and stores the child's pid in pid_out[0]
 */
JNIEXPORT jint JNICALL
Java_io_github_nicup14_debdroid_term_Pty_open(JNIEnv *env, jclass cls,
                                              jobjectArray argv_array,
                                              jobjectArray env_array,
                                              jstring cwd_string,
                                              jint rows, jint columns,
                                              jintArray pid_out)
{
    (void) cls;

    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master < 0) {
        throw_io(env, "Cannot open /dev/ptmx.");
        return -1;
    }

    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        close(master);
        throw_io(env, "Cannot unlock the pty.");
        return -1;
    }

    /* NOTE: ptsname is not reentrant, so its result is copied at once. */
    const char *slave_name_shared = ptsname(master);
    if (slave_name_shared == NULL) {
        close(master);
        throw_io(env, "Cannot name the pty.");
        return -1;
    }
    char slave_name[64];
    strncpy(slave_name, slave_name_shared, sizeof(slave_name) - 1);
    slave_name[sizeof(slave_name) - 1] = '\0';

    /* Sets UTF-8 input and DEL as erase, which is what the keyboard sends */
    struct termios tios;
    if (tcgetattr(master, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tios.c_cc[VERASE] = 0x7f;
        tcsetattr(master, TCSANOW, &tios);
    }

    struct winsize size = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
    };
    ioctl(master, TIOCSWINSZ, &size);

    char **argv = to_c_array(env, argv_array);
    char **envp = to_c_array(env, env_array);
    char *cwd = NULL;
    if (cwd_string != NULL) {
        const char *chars = (*env)->GetStringUTFChars(env, cwd_string, NULL);
        cwd = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, cwd_string, chars);
    }

    if (argv == NULL || argv[0] == NULL) {
        free_c_array(argv);
        free_c_array(envp);
        free(cwd);
        close(master);
        throw_io(env, "No command to run.");
        return -1;
    }

    pid_t pid = fork();

    if (pid < 0) {
        free_c_array(argv);
        free_c_array(envp);
        free(cwd);
        close(master);
        throw_io(env, "Cannot fork.");
        return -1;
    }

    if (pid == 0) {
        /* The child: async-signal-safe calls only from here to exec */
        sigset_t none;
        sigemptyset(&none);
        sigprocmask(SIG_SETMASK, &none, NULL);
        for (int sig = 1; sig < 32; sig++)
            signal(sig, SIG_DFL);

        close(master);
        setsid();

        int slave = open(slave_name, O_RDWR);
        if (slave < 0)
            _exit(126);
        ioctl(slave, TIOCSCTTY, 0);

        dup2(slave, 0);
        dup2(slave, 1);
        dup2(slave, 2);
        if (slave > 2)
            close(slave);

        /* Closes everything else the app had open, which the child must not hold */
        for (int fd = 3; fd < 1024; fd++)
            close(fd);

        if (cwd != NULL)
            chdir(cwd);
        if (envp != NULL)
            environ = envp;

        execvp(argv[0], argv);
        _exit(127);
    }

    free_c_array(argv);
    free_c_array(envp);
    free(cwd);

    jint pid_value = (jint) pid;
    (*env)->SetIntArrayRegion(env, pid_out, 0, 1, &pid_value);
    return master;
}

/* Tells the pty its new size, which raises SIGWINCH in the child */
JNIEXPORT void JNICALL
Java_io_github_nicup14_debdroid_term_Pty_resize(JNIEnv *env, jclass cls,
                                                jint fd, jint rows, jint columns)
{
    (void) env;
    (void) cls;
    struct winsize size = {
        .ws_row = (unsigned short) rows,
        .ws_col = (unsigned short) columns,
    };
    ioctl(fd, TIOCSWINSZ, &size);
}

/* Waits for the child, returning its exit code, or minus the signal that killed it */
JNIEXPORT jint JNICALL
Java_io_github_nicup14_debdroid_term_Pty_waitFor(JNIEnv *env, jclass cls, jint pid)
{
    (void) env;
    (void) cls;
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR)
            return -1;
    }
    if (WIFEXITED(status))
        return WEXITSTATUS(status);
    if (WIFSIGNALED(status))
        return -WTERMSIG(status);
    return 0;
}

/* Sends a signal to the child's whole session, as a terminal hangup would */
JNIEXPORT void JNICALL
Java_io_github_nicup14_debdroid_term_Pty_hangup(JNIEnv *env, jclass cls, jint pid)
{
    (void) env;
    (void) cls;
    if (pid > 0) {
        kill(-pid, SIGHUP);
        kill(pid, SIGHUP);
    }
}

/* Closes the master side, which also hangs up the slave */
JNIEXPORT void JNICALL
Java_io_github_nicup14_debdroid_term_Pty_close(JNIEnv *env, jclass cls, jint fd)
{
    (void) env;
    (void) cls;
    close(fd);
}
