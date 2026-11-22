#!/bin/bash
# Control the service over adb for testing, without the app:
#   ./tablet.sh start [idle=10 camera=front wall=1 ...] | stop | log | status
cd "$(dirname "$0")"
DEX=/data/local/tmp/halbwach.dex
LOG=/data/local/tmp/halbwach.log
STOP=/data/local/tmp/halbwach.stop
case "$1" in
  start)
    shift
    adb shell "touch $STOP; sleep 2; pkill -f '[i]o.github.blackcube4.halbwach.Main'" 2>/dev/null
    adb push build/halbwach.dex $DEX >/dev/null
    adb shell "CLASSPATH=$DEX setsid nohup app_process / io.github.blackcube4.halbwach.Main $* > $LOG 2>&1 < /dev/null &"
    sleep 5; adb shell cat $LOG ;;
  stop)   adb shell "touch $STOP"; sleep 2; adb shell cat $LOG | tail -3 ;;
  log)    adb shell cat $LOG ;;
  status) adb shell "pgrep -f '[i]o.github.blackcube4.halbwach.Main' >/dev/null && echo running || echo 'not running'" ;;
  *) echo "Usage: $0 start [idle=10 port=8765 token=... camera=front wall=1] | stop | log | status" ;;
esac
