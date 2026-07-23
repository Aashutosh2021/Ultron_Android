import os
import subprocess
import time

LOG_FILE = "log.txt"
ENROLLMENT_FILE = "enrollment voice.wav"
WAKEWORD_FILE = "hey jarvis.wav"

def run_cmd(cmd):
    print(f"Running: {cmd}")
    result = subprocess.run(cmd, shell=True, capture_output=True)
    return type('obj', (object,), {'stdout': result.stdout.decode('utf-8', errors='replace'), 'stderr': result.stderr.decode('utf-8', errors='replace')})()

def play_audio_file(filepath, blocking=True):
    print(f"Playing '{filepath}' from laptop speakers...")
    ps_cmd = f'powershell -c "(New-Object Media.SoundPlayer \'{filepath}\').PlaySync()"'
    if blocking:
        subprocess.run(ps_cmd, shell=True)
    else:
        return subprocess.Popen(ps_cmd, shell=True)

def main():
    print("=== Starting Custom ECAPA Voice Profile Auto-Test ===")
    
    if not os.path.exists(ENROLLMENT_FILE) or not os.path.exists(WAKEWORD_FILE):
        print("ERROR: Audio files not found!")
        return

    # 1. Clear old logs
    run_cmd("adb shell am force-stop com.flexfly.ultron")
    run_cmd("adb logcat -c")
    time.sleep(1)
    
    # 2. Force stop and restart the app
    run_cmd("adb shell am start -n com.flexfly.ultron/.MainActivity")
    
    print("Waiting for app to initialize and auto-start...")
    time.sleep(8)
    
    # 3. Play Wake Word
    print("--- ACCEPTANCE TEST ---")
    proc = play_audio_file(WAKEWORD_FILE, blocking=False)
    print("Waiting for pipeline to process audio...")
    time.sleep(12)
    
    # 4. Capture the logs and save to log.txt
    print(f"Capturing logs to {LOG_FILE}...")
    log_output = run_cmd("adb logcat -d -s AudioPipeline:* VoiceService:* GeminiLiveProvider:* ConversationEngine:* VadManager:* WakeWordManager:* OpenWakeWordDetector:* SpeakerVerificationManager:* EcapaTdnnVerifier:* VoiceSessionManager:* AssistantOverlayManager:*")
    
    with open(LOG_FILE, "w", encoding="utf-8") as f:
        f.write("=== AUTO TEST LOG ===\n")
        f.write(log_output.stdout)
        
    print("Done! Check log.txt for results.")

if __name__ == "__main__":
    main()
