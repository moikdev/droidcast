/* Owned-emulator test input; never bundled with the Android application. */
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <signal.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <unistd.h>
static volatile sig_atomic_t stopped;
static void stop(int sig) { (void)sig; stopped = 1; }
static long long now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC,&t); return (long long)t.tv_sec*1000000000LL+t.tv_nsec; }
static bool allowed(int c) { const int keys[]={29,97,56,100,42,54,125,126,30,46,47,51,1,16}; for(unsigned i=0;i<sizeof(keys)/sizeof(*keys);i++) if(c==keys[i]) return true; return false; }
static bool number(const char *s,long *n) { char *end; errno=0; *n=strtol(s,&end,10); return !errno && *s && !*end; }
static bool send_key(int fd,int code,int value) { struct input_event e[2]={{.type=EV_KEY,.code=code,.value=value},{.type=EV_SYN,.code=SYN_REPORT,.value=0}}; ssize_t n; do { n=write(fd,e,sizeof(e)); } while(n<0 && errno==EINTR); return n==(ssize_t)sizeof(e); }
int main(int argc,char **argv) {
 char qemu[PROP_VALUE_MAX]; __system_property_get("ro.kernel.qemu",qemu);
 if(getuid()!=0 || strcmp(qemu,"1") || argc<5 || (argc-2)%3 || (argc-2)/3>128) return 2;
 const char *prefix="/dev/input/event"; if(strncmp(argv[1],prefix,strlen(prefix)) || !argv[1][strlen(prefix)]) return 2;
 for(const char *s=argv[1]+strlen(prefix);*s;s++) if(*s<'0'||*s>'9') return 2;
 for(int i=2;i<argc;i+=3) { long c,v,d; if(!number(argv[i],&c)||!number(argv[i+1],&v)||!number(argv[i+2],&d)||!allowed(c)||v<0||v>2||d<0||d>1000000) return 2; }
 int fd=open(argv[1],O_WRONLY|O_CLOEXEC|O_NOFOLLOW); struct stat st;
 if(fd<0 || fstat(fd,&st) || !S_ISCHR(st.st_mode) || major(st.st_rdev)!=13) return 3;
 signal(SIGTERM,stop); signal(SIGINT,stop); bool pressed[KEY_MAX+1]={0}; int result=0;
 printf("["); bool comma=false;
 for(int i=2;i<argc && !stopped;i+=3) {
  int code=atoi(argv[i]),value=atoi(argv[i+1]); long delay=strtol(argv[i+2],NULL,10);
  long long before=now(); if(!send_key(fd,code,value)) { result=4; break; } long long after=now();
  if(value!=2) pressed[code]=value==1;
  printf("%s{\"code\":%d,\"value\":%d,\"before_ns\":%lld,\"after_ns\":%lld,\"delay_us\":%ld}",comma?",":"",code,value,before,after,delay);comma=true;
  struct timespec t={.tv_sec=delay/1000000,.tv_nsec=(delay%1000000)*1000};
  while(nanosleep(&t,&t)<0 && errno==EINTR && !stopped) {}
 }
 for(int key=0;key<=KEY_MAX;key++) if(pressed[key]) send_key(fd,key,0);
 printf("]\n"); close(fd); return stopped?5:result;
}
