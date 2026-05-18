variable "aws_region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "project_name" {
  description = "프로젝트 이름 (리소스 태그에 사용)"
  type        = string
  default     = "forescale"
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_a_cidr" {
  description = "퍼블릭 서브넷 A CIDR (ap-northeast-2a)"
  type        = string
  default     = "10.0.1.0/24"
}

variable "public_subnet_b_cidr" {
  description = "퍼블릭 서브넷 B CIDR (ap-northeast-2c)"
  type        = string
  default     = "10.0.3.0/24"
}

variable "private_subnet_cidr" {
  description = "프라이빗 서브넷 CIDR (ap-northeast-2a)"
  type        = string
  default     = "10.0.2.0/24"
}

variable "ubuntu_ami" {
  description = "Ubuntu 22.04 LTS AMI (서울 리전)"
  type        = string
  default     = "ami-042e76978adeb8c48" # Ubuntu 22.04 ap-northeast-2
}

variable "instance_type_app" {
  description = "App VM 인스턴스 타입"
  type        = string
  default     = "t3.small"
}

variable "instance_type_monitoring" {
  description = "모니터링 VM 인스턴스 타입"
  type        = string
  default     = "t3.small"
}

variable "instance_type_ai" {
  description = "AI VM 인스턴스 타입"
  type        = string
  default     = "t3.small"
}

variable "key_name" {
  description = "EC2 접속용 Key Pair 이름"
  type        = string
  default     = "forescale-key"
}