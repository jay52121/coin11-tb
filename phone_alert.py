def notify_phone(d, message="需要人工处理"):
    print("手机通知提醒:", message)
    try:
        d.shell("cmd media_session volume --stream 3 --set 12")
    except Exception as exc:
        print("设置手机音量失败", exc)
    try:
        d.shell(f"cmd notification post -S bigtext -t '淘金币脚本' taojinbi_alert '{message}'")
        return True
    except Exception as exc:
        print("发送手机通知失败", exc)
        return False
