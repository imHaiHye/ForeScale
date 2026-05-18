# outputs.tf

output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.main.id
}

output "public_subnet_a_id" {
  description = "퍼블릭 서브넷 A ID"
  value       = aws_subnet.public_a.id
}

output "public_subnet_b_id" {
  description = "퍼블릭 서브넷 B ID"
  value       = aws_subnet.public_b.id
}

output "private_subnet_id" {
  description = "프라이빗 서브넷 ID"
  value       = aws_subnet.private.id
}

# outputs.tf 기존 내용 아래에 추가

output "sg_alb_id" {
  description = "ALB 보안 그룹 ID"
  value       = aws_security_group.alb.id
}

output "sg_app_vm_id" {
  description = "App VM 보안 그룹 ID"
  value       = aws_security_group.app_vm.id
}

output "sg_monitoring_id" {
  description = "모니터링 VM 보안 그룹 ID"
  value       = aws_security_group.monitoring.id
}

output "sg_ai_vm_id" {
  description = "AI VM 보안 그룹 ID"
  value       = aws_security_group.ai_vm.id
}

output "nat_gateway_ip" {
  description = "NAT Gateway 공인 IP"
  value       = aws_eip.nat.public_ip
}

output "app_vm_1_private_ip" {
  description = "App VM 1 프라이빗 IP"
  value       = aws_instance.app_vm_1.private_ip
}

output "app_vm_2_private_ip" {
  description = "App VM 2 프라이빗 IP"
  value       = aws_instance.app_vm_2.private_ip
}

output "monitoring_private_ip" {
  description = "모니터링 VM 프라이빗 IP"
  value       = aws_instance.monitoring.private_ip
}

output "ai_vm_private_ip" {
  description = "AI VM 프라이빗 IP"
  value       = aws_instance.ai_vm.private_ip
}

output "alb_dns_name" {
  description = "ALB DNS 주소 (외부 접근용)"
  value       = aws_lb.main.dns_name
}

output "app_target_group_arn" {
  description = "App Target Group ARN (boto3 Scale-out에 사용)"
  value       = aws_lb_target_group.app.arn
}