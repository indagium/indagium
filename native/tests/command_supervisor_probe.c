#define _POSIX_C_SOURCE 200809L

#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static void wait_millis(long millis) {
    struct timespec pause = {millis / 1000, (millis % 1000) * 1000000};
    nanosleep(&pause, NULL);
}

static int argv_mode(int argc, char **argv) {
    for (int i = 2; i < argc; ++i) {
        puts(argv[i]);
    }
    return 0;
}

static int spawn_child(const char *pid_path, const char *sentinel_path, int wait_for_child) {
    pid_t child = fork();
    if (child < 0) return 90;
    if (child == 0) {
        (void)signal(SIGTERM, SIG_IGN);
        FILE *pid_file = fopen(pid_path, "wb");
        if (pid_file != NULL) {
            fprintf(pid_file, "%ld", (long)getpid());
            fclose(pid_file);
        }
        wait_millis(900);
        FILE *sentinel = fopen(sentinel_path, "wb");
        if (sentinel != NULL) {
            fputs("survived", sentinel);
            fclose(sentinel);
        }
        _exit(0);
    }
    for (int i = 0; i < 100 && access(pid_path, F_OK) != 0; ++i) wait_millis(5);
    if (wait_for_child) {
        struct timespec forever = {60, 0};
        nanosleep(&forever, NULL);
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 2) return 89;
    if (strcmp(argv[1], "--argv") == 0) return argv_mode(argc, argv);
    if (strcmp(argv[1], "--environment") == 0) {
        char cwd[32768];
        if (getcwd(cwd, sizeof(cwd)) == NULL) return 86;
        const char *value = getenv("INDAGIUM_SUPERVISOR_TEST");
        printf("%s|%s\n", cwd, value == NULL ? "" : value);
        return 0;
    }
    if (strcmp(argv[1], "--stdin") == 0) {
        char buffer[4096];
        size_t count;
        while ((count = fread(buffer, 1, sizeof(buffer), stdin)) > 0) fwrite(buffer, 1, count, stdout);
        return ferror(stdin) ? 88 : 0;
    }
    if (strcmp(argv[1], "--emit") == 0 && argc == 3) {
        long count = strtol(argv[2], NULL, 10);
        for (long i = 0; i < count; ++i) fputc('o', stdout);
        for (long i = 0; i < count; ++i) fputc('e', stderr);
        return 0;
    }
    if (strcmp(argv[1], "--exit") == 0 && argc == 3) return atoi(argv[2]);
    if ((strcmp(argv[1], "--spawn-resistant-child") == 0 || strcmp(argv[1], "--spawn-and-wait") == 0) && argc == 4) {
        return spawn_child(argv[2], argv[3], strcmp(argv[1], "--spawn-and-wait") == 0);
    }
    return 87;
}
