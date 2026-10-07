#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

/* The Java process owns this supervisor. The actual command gets a fresh session/process group so
 * descendants remain signalable after the command itself exits or reparents them. */

static volatile sig_atomic_t interrupted_signal = 0;

static void on_signal(int signal_number) {
    interrupted_signal = signal_number;
}

static void status_file(const char *path, const char *value) {
    FILE *file = fopen(path, "wb");
    if (file == NULL) return;
    fwrite(value, 1, strlen(value), file);
    fclose(file);
}

static int group_exists(pid_t process_group) {
    if (kill(-process_group, 0) == 0) return 1;
    return errno == EPERM;
}

static void terminate_group(pid_t process_group) {
    if (kill(-process_group, SIGTERM) != 0 && errno == ESRCH) return;
    const long grace_ms = 250;
    struct timespec pause = {0, 10 * 1000 * 1000};
    for (long elapsed = 0; elapsed < grace_ms && group_exists(process_group); elapsed += 10) nanosleep(&pause, NULL);
    if (group_exists(process_group)) kill(-process_group, SIGKILL);
}

static void terminate_command(pid_t process_group) {
    (void)kill(-process_group, SIGTERM);
    (void)kill(process_group, SIGTERM);
    const long grace_ms = 250;
    struct timespec pause = {0, 10 * 1000 * 1000};
    for (long elapsed = 0; elapsed < grace_ms; elapsed += 10) {
        int root_exists = kill(process_group, 0) == 0 || errno == EPERM;
        if (!root_exists && !group_exists(process_group)) break;
        nanosleep(&pause, NULL);
    }
    (void)kill(-process_group, SIGKILL);
    (void)kill(process_group, SIGKILL);
}

static int child_exit_code(int status) {
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 125;
}

int main(int argc, char **argv) {
    if (argc < 5 || strcmp(argv[1], "--status") != 0 || strcmp(argv[3], "--") != 0 || argv[4][0] == '\0') {
        fputs("invalid command supervisor arguments\n", stderr);
        return 125;
    }
    const char *status_path = argv[2];
    int exec_error_pipe[2];
    if (pipe(exec_error_pipe) != 0) {
        char message[80];
        snprintf(message, sizeof(message), "error:%d", errno);
        status_file(status_path, message);
        return 125;
    }
    if (fcntl(exec_error_pipe[1], F_SETFD, FD_CLOEXEC) != 0) {
        char message[80];
        snprintf(message, sizeof(message), "error:%d", errno);
        status_file(status_path, message);
        close(exec_error_pipe[0]);
        close(exec_error_pipe[1]);
        return 125;
    }
    if (fcntl(exec_error_pipe[0], F_SETFL, O_NONBLOCK) != 0) {
        char message[80];
        snprintf(message, sizeof(message), "error:%d", errno);
        status_file(status_path, message);
        close(exec_error_pipe[0]);
        close(exec_error_pipe[1]);
        return 125;
    }

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = on_signal;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);

    pid_t child = fork();
    if (child < 0) {
        char message[80];
        snprintf(message, sizeof(message), "error:%d", errno);
        status_file(status_path, message);
        close(exec_error_pipe[0]);
        close(exec_error_pipe[1]);
        return 125;
    }
    if (child == 0) {
        close(exec_error_pipe[0]);
        if (setsid() < 0) {
            int error_number = errno;
            (void)write(exec_error_pipe[1], &error_number, sizeof(error_number));
            _exit(125);
        }
        execvp(argv[4], &argv[4]);
        int error_number = errno;
        (void)write(exec_error_pipe[1], &error_number, sizeof(error_number));
        _exit(127);
    }

    close(exec_error_pipe[1]);
    int exec_error = 0;
    ssize_t received = 0;
    int initialization_cancelled = 0;
    int read_error = 0;
    struct timespec initialization_pause = {0, 10 * 1000 * 1000};
    while (received < (ssize_t)sizeof(exec_error)) {
        if (interrupted_signal != 0) {
            initialization_cancelled = 1;
            terminate_command(child);
            break;
        }
        ssize_t count = read(exec_error_pipe[0], ((char *)&exec_error) + received, sizeof(exec_error) - (size_t)received);
        if (count < 0 && errno == EINTR) {
            continue;
        }
        if (count < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            nanosleep(&initialization_pause, NULL);
            continue;
        }
        if (count < 0) {
            read_error = errno;
            break;
        }
        if (count == 0) break;
        received += count;
    }
    close(exec_error_pipe[0]);
    if (initialization_cancelled) {
        while (waitpid(child, NULL, 0) < 0 && errno == EINTR) { }
        terminate_group(child);
        return 128 + interrupted_signal;
    } else if (read_error != 0 || (received != 0 && received != (ssize_t)sizeof(exec_error))) {
        char message[80];
        snprintf(message, sizeof(message), "error:%d", read_error == 0 ? EIO : read_error);
        status_file(status_path, message);
        terminate_command(child);
    } else if (received > 0) {
        char message[80];
        snprintf(message, sizeof(message), "error:%d", exec_error);
        status_file(status_path, message);
    } else {
        status_file(status_path, "started");
    }

    if (interrupted_signal != 0) terminate_group(child);

    int child_status = 0;
    pid_t waited = 0;
    int wait_error = 0;
    struct timespec poll_pause = {0, 10 * 1000 * 1000};
    while (waited == 0) {
        if (interrupted_signal != 0) terminate_command(child);
        waited = waitpid(child, &child_status, WNOHANG);
        if (waited == 0) nanosleep(&poll_pause, NULL);
        else if (waited < 0 && errno == EINTR) waited = 0;
        else if (waited < 0) wait_error = errno;
    }

    terminate_group(child);
    if (wait_error != 0) return interrupted_signal == 0 ? 125 : 128 + interrupted_signal;
    return interrupted_signal == 0 ? child_exit_code(child_status) : 128 + interrupted_signal;
}
