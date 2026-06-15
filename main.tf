# main.tf

terraform {
  required_version = ">= 1.7.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# ── VPC ──────────────────────────────────────────────────
resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true # VM에 DNS 이름 부여 (Prometheus 스크랩에 필요)
  enable_dns_support   = true

  tags = {
    Name    = "${var.project_name}-vpc"
    Project = var.project_name
  }
}

# ── 퍼블릭 서브넷 A (ap-northeast-2a) ────────────────────
resource "aws_subnet" "public_a" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.public_subnet_a_cidr
  availability_zone       = "ap-northeast-2a"
  map_public_ip_on_launch = true # 여기 VM은 공인IP 자동 부여

  tags = {
    Name    = "${var.project_name}-public-a"
    Project = var.project_name
  }
}

# ── 퍼블릭 서브넷 B (ap-northeast-2c) ────────────────────
# ALB 멀티AZ 요건을 위해 필요
resource "aws_subnet" "public_b" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.public_subnet_b_cidr
  availability_zone       = "ap-northeast-2c"
  map_public_ip_on_launch = true

  tags = {
    Name    = "${var.project_name}-public-b"
    Project = var.project_name
  }
}

# ── 프라이빗 서브넷 (ap-northeast-2a) ────────────────────
# App VM, 모니터링 VM, AI VM 전부 여기
resource "aws_subnet" "private" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = var.private_subnet_cidr
  availability_zone = "ap-northeast-2a"

  tags = {
    Name    = "${var.project_name}-private"
    Project = var.project_name
  }
}

# ── 인터넷 게이트웨이 ─────────────────────────────────────
# 퍼블릭 서브넷이 인터넷과 통신하는 출입구
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name    = "${var.project_name}-igw"
    Project = var.project_name
  }
}

# ── 퍼블릭 라우팅 테이블 ──────────────────────────────────
# 퍼블릭 서브넷 → 인터넷 게이트웨이로 나가는 규칙
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"                  # 모든 외부 트래픽을
    gateway_id = aws_internet_gateway.main.id # IGW로 보냄
  }

  tags = {
    Name    = "${var.project_name}-rt-public"
    Project = var.project_name
  }
}

# ── 퍼블릭 서브넷 A → 라우팅 테이블 연결 ─────────────────
resource "aws_route_table_association" "public_a" {
  subnet_id      = aws_subnet.public_a.id
  route_table_id = aws_route_table.public.id
}

# ── 퍼블릭 서브넷 B → 라우팅 테이블 연결 ─────────────────
resource "aws_route_table_association" "public_b" {
  subnet_id      = aws_subnet.public_b.id
  route_table_id = aws_route_table.public.id
}

# 프라이빗 라우팅 테이블은 NAT GW 만든 후 추가 예정

# ── 보안 그룹 1: ALB용 ───────────────────────────────────
# 인터넷 → ALB 허용
resource "aws_security_group" "alb" {
  name        = "${var.project_name}-sg-alb"
  description = "ALB security group - external traffic"
  vpc_id      = aws_vpc.main.id

  # 인바운드: 인터넷에서 들어오는 트래픽 허용
  ingress {
    description = "HTTP app traffic"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"] # 모든 인터넷 허용
  }

  ingress {
    description = "Grafana dashboard external access"
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # 아웃바운드: 모든 트래픽 허용 (VM으로 전달)
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1" # 전체 프로토콜
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name    = "${var.project_name}-sg-alb"
    Project = var.project_name
  }
}

# ── 보안 그룹 2: App VM용 ────────────────────────────────
# ALB → App VM 허용 / 모니터링 VM → Node Exporter 허용
resource "aws_security_group" "app_vm" {
  name        = "${var.project_name}-sg-app"
  description = "App VM security group"
  vpc_id      = aws_vpc.main.id

  # ALB에서만 8080(Spring Boot) 허용
  ingress {
    description     = "Spring Boot from ALB only"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id] # ALB SG만 허용
  }

  # 추가 - AI VM에서 허용
  ingress {
    description     = "Spring Boot from AI VM"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.ai_vm.id]  # ← 추가
  }

  # 프라이빗 서브넷 내부에서 9100(Node Exporter) 허용
  # Prometheus가 메트릭 스크랩하는 포트
  ingress {
    description = "Node Exporter from private subnet"
    from_port   = 9100
    to_port     = 9100
    protocol    = "tcp"
    cidr_blocks = [var.private_subnet_cidr]
  }

  # SSH - 디버깅용 (나중에 Bastion 설정 전까지 임시)
  ingress {
    description = "SSH from private subnet"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.private_subnet_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name    = "${var.project_name}-sg-app"
    Project = var.project_name
  }
}

# ── 보안 그룹 3: 모니터링 VM용 ──────────────────────────
# Prometheus / Grafana VM
resource "aws_security_group" "monitoring" {
  name        = "${var.project_name}-sg-monitoring"
  description = "Monitoring VM security group"
  vpc_id      = aws_vpc.main.id

  # Prometheus - AI VM에서 쿼리
  ingress {
    description = "Prometheus from private subnet"
    from_port   = 9090
    to_port     = 9090
    protocol    = "tcp"
    cidr_blocks = [var.private_subnet_cidr]
  }

  # Grafana - ALB를 통해 외부 접근
  ingress {
    description     = "Grafana from ALB only"
    from_port       = 3000
    to_port         = 3000
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # 프라이빗 서브넷 내부에서 직접 접근
  ingress {
    description = "Grafana from private subnet"
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = [var.private_subnet_cidr]
  }

  # SSH - 디버깅용
  ingress {
    description = "SSH from private subnet"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.private_subnet_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name    = "${var.project_name}-sg-monitoring"
    Project = var.project_name
  }
}

# ── 보안 그룹 4: AI VM용 ─────────────────────────────────
# FastAPI 서버 / Prophet 모델 / boto3
resource "aws_security_group" "ai_vm" {
  name        = "${var.project_name}-sg-ai"
  description = "AI VM security group"
  vpc_id      = aws_vpc.main.id

  # FastAPI - 프라이빗 내부에서만 접근
  ingress {
    description = "FastAPI from private subnet"
    from_port   = 8000
    to_port     = 8000
    protocol    = "tcp"
    cidr_blocks = [var.private_subnet_cidr]
  }

  # SSH - 디버깅용
  ingress {
    description = "SSH from private subnet"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.private_subnet_cidr]
  }

  # 추가 - 모니터링 VM에서 9091 접근
  ingress {
    description     = "Port 9091 from monitoring VM"
    from_port       = 9091
    to_port         = 9091
    protocol        = "tcp"
    security_groups = [aws_security_group.monitoring.id]
  }

  # 아웃바운드 전체 허용 (boto3 AWS API 호출, Prometheus 쿼리)
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name    = "${var.project_name}-sg-ai"
    Project = var.project_name
  }
}

# ── Elastic IP (NAT Gateway에 붙일 고정 공인 IP) ─────────
resource "aws_eip" "nat" {
  domain = "vpc"

  tags = {
    Name    = "${var.project_name}-eip-nat"
    Project = var.project_name
  }

  # IGW가 먼저 있어야 EIP가 정상 동작
  depends_on = [aws_internet_gateway.main]
}

# ── NAT Gateway ───────────────────────────────────────────
# 퍼블릭 서브넷 A에 위치 (프라이빗 서브넷과 같은 AZ)
resource "aws_nat_gateway" "main" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public_a.id # 퍼블릭 서브넷에 위치

  tags = {
    Name    = "${var.project_name}-nat"
    Project = var.project_name
  }

  depends_on = [aws_internet_gateway.main]
}

# ── 프라이빗 라우팅 테이블 ────────────────────────────────
# 프라이빗 서브넷 → NAT GW로 나가는 규칙
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main.id # NAT GW로 보냄
  }

  tags = {
    Name    = "${var.project_name}-rt-private"
    Project = var.project_name
  }
}

# ── 프라이빗 서브넷 → 라우팅 테이블 연결 ─────────────────
resource "aws_route_table_association" "private" {
  subnet_id      = aws_subnet.private.id
  route_table_id = aws_route_table.private.id
}

# ── Key Pair ──────────────────────────────────────────────
resource "aws_key_pair" "main" {
  key_name   = var.key_name
  public_key = file("${path.module}/forescale-key.pub")

  tags = {
    Name    = "${var.project_name}-keypair"
    Project = var.project_name
  }
}

# ── App VM 1 ──────────────────────────────────────────────
resource "aws_instance" "app_vm_1" {
  ami                    = var.ubuntu_ami
  instance_type          = var.instance_type_app
  subnet_id              = aws_subnet.private.id
  vpc_security_group_ids = [aws_security_group.app_vm.id]
  key_name               = aws_key_pair.main.key_name
  private_ip = "10.0.2.40"   # IP 고정

  # 부팅 시 Node Exporter 자동 설치
  user_data            = file("${path.module}/userdata/app-vm-setup.sh")
  iam_instance_profile = aws_iam_instance_profile.ssm.name

  # 루트 볼륨 20GB
  root_block_device {
    volume_size = 20
    volume_type = "gp3"
  }

  tags = {
    Name    = "${var.project_name}-app-vm-1"
    Project = var.project_name
    Role    = "app"
  }
}

# ── App VM 2 ──────────────────────────────────────────────
resource "aws_instance" "app_vm_2" {
  ami                    = var.ubuntu_ami
  instance_type          = var.instance_type_app
  subnet_id              = aws_subnet.private.id
  vpc_security_group_ids = [aws_security_group.app_vm.id]
  key_name               = aws_key_pair.main.key_name
  private_ip = "10.0.2.218"  # IP 고정

  user_data            = file("${path.module}/userdata/app-vm-setup.sh")
  iam_instance_profile = aws_iam_instance_profile.ssm.name

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
  }

  tags = {
    Name    = "${var.project_name}-app-vm-2"
    Project = var.project_name
    Role    = "app"
  }
}

# ── 모니터링 VM ───────────────────────────────────────────
resource "aws_instance" "monitoring" {
  ami                    = var.ubuntu_ami
  instance_type          = var.instance_type_monitoring
  subnet_id              = aws_subnet.private.id
  vpc_security_group_ids = [aws_security_group.monitoring.id]
  key_name               = aws_key_pair.main.key_name
  iam_instance_profile   = aws_iam_instance_profile.ssm.name
  user_data              = file("${path.module}/userdata/app-vm-setup.sh")
  private_ip = "10.0.2.202"  # IP 고정

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
  }

  tags = {
    Name    = "${var.project_name}-monitoring"
    Project = var.project_name
    Role    = "monitoring"
  }
}

# ── AI VM ─────────────────────────────────────────────────
resource "aws_instance" "ai_vm" {
  ami                    = var.ubuntu_ami
  instance_type          = var.instance_type_ai
  subnet_id              = aws_subnet.private.id
  vpc_security_group_ids = [aws_security_group.ai_vm.id]
  key_name               = aws_key_pair.main.key_name
  iam_instance_profile   = aws_iam_instance_profile.ssm.name
  user_data              = file("${path.module}/userdata/app-vm-setup.sh")
  private_ip = "10.0.2.148"  # IP 고정

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
  }

  tags = {
    Name    = "${var.project_name}-ai-vm"
    Project = var.project_name
    Role    = "ai"
  }
}

# ── Target Group ──────────────────────────────────────────
# ALB가 트래픽을 보낼 VM 그룹
resource "aws_lb_target_group" "app" {
  name     = "${var.project_name}-tg-app"
  port     = 8080
  protocol = "HTTP"
  vpc_id   = aws_vpc.main.id

  # 헬스체크 설정
  # App VM의 Spring Boot가 /health 에 응답해야 정상으로 판단
  health_check {
    enabled             = true
    path                = "/health"
    port                = "8080"
    protocol            = "HTTP"
    healthy_threshold   = 2     # 2번 연속 성공 → 정상
    unhealthy_threshold = 3     # 3번 연속 실패 → 비정상
    interval            = 10    # 10초마다 체크
    timeout             = 5     # 5초 안에 응답 없으면 실패
    matcher             = "200" # HTTP 200 응답이어야 정상
  }

  tags = {
    Name    = "${var.project_name}-tg-app"
    Project = var.project_name
  }
}

# ── Target Group - Grafana ────────────────────────────────
# Grafana 대시보드 외부 접근용
resource "aws_lb_target_group" "grafana" {
  name     = "${var.project_name}-tg-grafana"
  port     = 3000
  protocol = "HTTP"
  vpc_id   = aws_vpc.main.id

  health_check {
    enabled             = true
    path                = "/api/health"
    port                = "3000"
    protocol            = "HTTP"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 10
    timeout             = 5
    matcher             = "200"
  }

  tags = {
    Name    = "${var.project_name}-tg-grafana"
    Project = var.project_name
  }
}

# ── ALB 본체 ──────────────────────────────────────────────
resource "aws_lb" "main" {
  name               = "${var.project_name}-alb"
  internal           = false # 외부 인터넷에서 접근 가능
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]

  # 멀티 AZ - 퍼블릭 서브넷 2개에 걸쳐 배치
  subnets = [
    aws_subnet.public_a.id,
    aws_subnet.public_b.id
  ]

  tags = {
    Name    = "${var.project_name}-alb"
    Project = var.project_name
  }
}

# ── Listener 1: 포트 80 → App VM ─────────────────────────
resource "aws_lb_listener" "app" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# ── Listener 2: 포트 3000 → Grafana ──────────────────────
resource "aws_lb_listener" "grafana" {
  load_balancer_arn = aws_lb.main.arn
  port              = 3000
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.grafana.arn
  }
}

# ── App VM 1 → Target Group 등록 ─────────────────────────
resource "aws_lb_target_group_attachment" "app_vm_1" {
  target_group_arn = aws_lb_target_group.app.arn
  target_id        = aws_instance.app_vm_1.id
  port             = 8080
}

# ── App VM 2 → Target Group 등록 ─────────────────────────
resource "aws_lb_target_group_attachment" "app_vm_2" {
  target_group_arn = aws_lb_target_group.app.arn
  target_id        = aws_instance.app_vm_2.id
  port             = 8080
}

# ── 모니터링 VM → Grafana Target Group 등록 ──────────────
resource "aws_lb_target_group_attachment" "grafana" {
  target_group_arn = aws_lb_target_group.grafana.arn
  target_id        = aws_instance.monitoring.id
  port             = 3000
}

# ── SSM IAM 역할 ──────────────────────────────────────────
resource "aws_iam_instance_profile" "ssm" {
  name = "${var.project_name}-ssm-profile-new"
  role = aws_iam_role.ssm.name
}

resource "aws_iam_role" "ssm" {
  name = "${var.project_name}-ssm-role-new"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.ssm.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}