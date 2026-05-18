# recovery/provisioner.py

import boto3
import json
import time
from datetime import datetime

# ── 설정값 ────────────────────────────────────────────────
REGION            = "ap-northeast-2"
GOLDEN_AMI_ID     = "ami-07cee165a8d7d577f"
PRIVATE_SUBNET_ID = "subnet-0dbf1ff7584d7e43b"
SG_APP_VM_ID      = "sg-062b29a22f9497bf2"
TARGET_GROUP_ARN  = "arn:aws:elasticloadbalancing:ap-northeast-2:361103952283:targetgroup/forescale-tg-app/88fd4b5f31f2446b"
INSTANCE_TYPE     = "t3.small"
FILE_SD_PATH      = "/opt/prometheus/file_sd.json"

# boto3 클라이언트
ec2   = boto3.client("ec2",   region_name=REGION)
elbv2 = boto3.client("elbv2", region_name=REGION)
ssm   = boto3.client("ssm",   region_name=REGION)


# ── 모니터링 VM 인스턴스 ID 자동 조회 ─────────────────────
def get_monitoring_instance_id() -> str:
    response = ec2.describe_instances(
        Filters=[
            {"Name": "tag:Role",    "Values": ["monitoring"]},
            {"Name": "tag:Project", "Values": ["forescale"]},
            {"Name": "instance-state-name", "Values": ["running"]}
        ]
    )
    for r in response["Reservations"]:
        for i in r["Instances"]:
            return i["InstanceId"]
    raise Exception("모니터링 VM을 찾을 수 없습니다")


# ── Scale-out ─────────────────────────────────────────────
def scale_out(reason: str) -> dict:
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    vm_name   = f"forescale-recovery-{timestamp}"

    print(f"[{timestamp}] Scale-out 시작: {vm_name}")
    print(f"[{timestamp}] 원인: {reason}")

    # 1. Golden AMI로 EC2 생성
    response = ec2.run_instances(
        ImageId          = GOLDEN_AMI_ID,
        InstanceType     = INSTANCE_TYPE,
        MinCount         = 1,
        MaxCount         = 1,
        SubnetId         = PRIVATE_SUBNET_ID,
        SecurityGroupIds = [SG_APP_VM_ID],
        IamInstanceProfile = {"Name": "forescale-ssm-profile-new"},
        TagSpecifications=[{
            "ResourceType": "instance",
            "Tags": [
                {"Key": "Name",    "Value": vm_name},
                {"Key": "Project", "Value": "forescale"},
                {"Key": "Role",    "Value": "recovery"},
                {"Key": "Reason",  "Value": reason},
            ]
        }]
    )

    instance_id = response["Instances"][0]["InstanceId"]
    print(f"[{timestamp}] EC2 생성됨: {instance_id}")

    # 2. running 상태까지 대기
    print(f"[{timestamp}] VM 기동 대기 중...")
    waiter = ec2.get_waiter("instance_running")
    waiter.wait(InstanceIds=[instance_id])

    # 3. 프라이빗 IP 가져오기
    detail     = ec2.describe_instances(InstanceIds=[instance_id])
    private_ip = detail["Reservations"][0]["Instances"][0]["PrivateIpAddress"]
    print(f"[{timestamp}] VM 기동 완료: {private_ip}")

    # 4. ALB 먼저 등록
    elbv2.register_targets(
        TargetGroupArn = TARGET_GROUP_ARN,
        Targets        = [{"Id": instance_id, "Port": 8080}]
    )
    print(f"[{timestamp}] ALB Target Group 등록 완료")

    # 5. ALB 헬스체크 통과까지 대기
    print(f"[{timestamp}] ALB 헬스체크 대기 중...")
    _wait_for_alb_healthy(instance_id)

    # 6. Prometheus file_sd 업데이트
    print(f"[{timestamp}] Prometheus 타겟 추가 중...")
    _add_to_prometheus_targets(private_ip, vm_name)
    print(f"[{timestamp}] Prometheus 타겟 추가 완료")

    print(f"[{timestamp}] Scale-out 완료: {vm_name} ({private_ip})")

    return {
        "instance_id": instance_id,
        "private_ip":  private_ip,
        "vm_name":     vm_name,
        "status":      "provisioned"
    }


# ── Scale-in ──────────────────────────────────────────────
def scale_in(instance_id: str) -> dict:
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    print(f"[{timestamp}] Scale-in 시작: {instance_id}")

    # 1. 프라이빗 IP 먼저 저장
    detail     = ec2.describe_instances(InstanceIds=[instance_id])
    private_ip = detail["Reservations"][0]["Instances"][0]["PrivateIpAddress"]

    # 2. ALB에서 드레이닝
    elbv2.deregister_targets(
        TargetGroupArn = TARGET_GROUP_ARN,
        Targets        = [{"Id": instance_id}]
    )
    print(f"[{timestamp}] ALB 드레이닝 중... (30초 대기)")
    time.sleep(30)

    # 3. EC2 종료
    ec2.terminate_instances(InstanceIds=[instance_id])
    print(f"[{timestamp}] EC2 종료 완료: {instance_id}")

    # 4. file_sd에서 제거
    print(f"[{timestamp}] Prometheus 타겟 제거 중...")
    _remove_from_prometheus_targets(private_ip)
    print(f"[{timestamp}] Prometheus 타겟 제거 완료")

    return {
        "instance_id": instance_id,
        "status":      "terminated"
    }


# ── 복구 VM 목록 조회 ─────────────────────────────────────
def get_recovery_vms() -> list:
    """Role=recovery 태그 running VM만 반환"""
    response = ec2.describe_instances(
        Filters=[
            {"Name": "tag:Role",    "Values": ["recovery"]},
            {"Name": "tag:Project", "Values": ["forescale"]},
            {"Name": "instance-state-name", "Values": ["running"]}
        ]
    )
    vms = []
    for r in response["Reservations"]:
        for i in r["Instances"]:
            vms.append({
                "instance_id": i["InstanceId"],
                "private_ip":  i["PrivateIpAddress"]
            })
    return vms


# ── 내부 함수 ─────────────────────────────────────────────
def _wait_for_alb_healthy(instance_id: str, max_wait: int = 180):
    """ALB 헬스체크 통과까지 대기 (최대 3분)"""
    for i in range(max_wait // 10):
        response = elbv2.describe_target_health(
            TargetGroupArn = TARGET_GROUP_ARN,
            Targets        = [{"Id": instance_id, "Port": 8080}]
        )
        healths = response.get("TargetHealthDescriptions", [])
        if healths and healths[0]["TargetHealth"]["State"] == "healthy":
            print(f"ALB 헬스체크 통과 ({(i+1)*10}초)")
            return True
        state = healths[0]["TargetHealth"]["State"] if healths else "unknown"
        print(f"ALB 헬스체크 대기 중... ({(i+1)*10}초) 현재상태: {state}")
        time.sleep(10)
    print("ALB 헬스체크 타임아웃 — 계속 진행")
    return False


def _wait_for_ssm_command(command_id: str, instance_id: str) -> dict:
    """SSM 명령 완료까지 polling"""
    for _ in range(30):
        time.sleep(1)
        try:
            result = ssm.get_command_invocation(
                CommandId  = command_id,
                InstanceId = instance_id
            )
            status = result["Status"]
            if status == "Success":
                return result
            elif status in ["Failed", "Cancelled", "TimedOut"]:
                raise Exception(
                    f"SSM 명령 실패: {status}\n"
                    f"{result['StandardErrorContent']}"
                )
        except ssm.exceptions.InvocationDoesNotExist:
            continue
    raise Exception("SSM 명령 타임아웃 (30초)")


def _add_to_prometheus_targets(ip: str, name: str):
    """SSM으로 모니터링 VM의 file_sd.json에 추가"""
    monitoring_id = get_monitoring_instance_id()

    # labels 형식: vm + role 로 통일
    py_cmd = (
        f"import json; "
        f"f=open('{FILE_SD_PATH}'); t=json.load(f); f.close(); "
        f"t.append({{'targets':['{ip}:9100'],'labels':{{'vm':'{name}','role':'recovery'}}}}); "
        f"f=open('{FILE_SD_PATH}','w'); json.dump(t,f,indent=2); f.close(); "
        f"print('추가완료:{ip}')"
    )

    shell_cmd = (
        f"python3 -c \"{py_cmd}\""
    )

    response   = ssm.send_command(
        InstanceIds  = [monitoring_id],
        DocumentName = "AWS-RunShellScript",
        Parameters   = {"commands": [shell_cmd]}
    )
    result = _wait_for_ssm_command(
        response["Command"]["CommandId"], monitoring_id
    )
    print(f"file_sd 추가 결과: {result['StandardOutputContent'].strip()}")


def _remove_from_prometheus_targets(ip: str):
    """SSM으로 모니터링 VM의 file_sd.json에서 제거"""
    monitoring_id = get_monitoring_instance_id()

    py_cmd = (
        f"import json; "
        f"f=open('{FILE_SD_PATH}'); t=json.load(f); f.close(); "
        f"t=[x for x in t if '{ip}:9100' not in x.get('targets',[])]; "
        f"f=open('{FILE_SD_PATH}','w'); json.dump(t,f,indent=2); f.close(); "
        f"print('제거완료:{ip}')"
    )

    shell_cmd = (
        f"[ -f '{FILE_SD_PATH}' ] && "
        f"python3 -c \"{py_cmd}\" || echo '파일없음-스킵'"
    )

    response   = ssm.send_command(
        InstanceIds  = [monitoring_id],
        DocumentName = "AWS-RunShellScript",
        Parameters   = {"commands": [shell_cmd]}
    )
    result = _wait_for_ssm_command(
        response["Command"]["CommandId"], monitoring_id
    )
    print(f"file_sd 제거 결과: {result['StandardOutputContent'].strip()}")


# ── 단독 테스트 ───────────────────────────────────────────
if __name__ == "__main__":
    print("=" * 50)
    print("Scale-out 단독 테스트 시작")
    print("=" * 50)

    result = scale_out(reason="manual-test")
    print(f"\n생성된 VM: {result}")
    print("\nAWS 콘솔에서 확인하세요:")
    print(f"  EC2 인스턴스: {result['instance_id']}")
    print(f"  프라이빗 IP:  {result['private_ip']}")
    print(f"  ALB 대상 그룹 등록 확인")

    input("\n확인 완료 후 엔터를 누르면 Scale-in 테스트를 시작합니다...")

    print("\n" + "=" * 50)
    print("Scale-in 단독 테스트 시작")
    print("=" * 50)
    scale_in(result["instance_id"])
    print("\n테스트 완료")
    print("AWS 콘솔에서 VM이 terminated 됐는지 확인하세요.")