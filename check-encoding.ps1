# ============================================
# 编码检查与修复脚本 (PowerShell)
# 用途：批量检查和修复Java文件编码问题
# 使用方法：在项目根目录运行此脚本
# ============================================

param(
    [switch]$Fix,      # 是否自动修复
    [switch]$Verbose   # 显示详细信息
)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Java文件编码检查工具" -ForegroundColor Cyan
Write-Host "  检查BOM头、首字母缺失、中文乱码等问题" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaFiles = Get-ChildItem -Path $projectRoot -Recurse -Filter "*.java" -ErrorAction SilentlyContinue

$totalCount = 0
$bomCount = 0
$missingFirstChar = 0
$garbledCount = 0
$fixedCount = 0

foreach ($file in $javaFiles) {
    $totalCount++
    $filePath = $file.FullName
    $relativePath = $filePath.Replace($projectRoot, "")
    
    # 跳过target目录
    if ($filePath -match "\\target\\") {
        continue
    }
    
    try {
        $bytes = [System.IO.File]::ReadAllBytes($filePath)
        $hasIssue = $false
        
        # 检查1: BOM头检测
        if ($bytes.Length -ge 3 -and 
            $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
            $bomCount++
            $hasIssue = $true
            
            if ($Verbose) {
                Write-Host "[BOM] 发现BOM头: $relativePath" -ForegroundColor Yellow
            }
            
            if ($Fix) {
                # 移除BOM头
                $bytes = $bytes[3..($bytes.Length-1)]
                [System.IO.File]::WriteAllBytes($filePath, $bytes)
                $fixedCount++
                Write-Host "[FIXED] 已移除BOM: $relativePath" -ForegroundColor Green
            }
        }
        
        # 检查2: 首字母缺失检测（package关键字）
        if ($bytes.Length -gt 10) {
            $content = [System.Text.Encoding]::UTF8.GetString($bytes)
            
            # 检查package关键字是否完整
            if ($content.TrimStart().StartsWith("ackage ")) {
                $missingFirstChar++
                $hasIssue = $true
                
                if ($Verbose) {
                    Write-Host "[MISSING] 首字母p缺失: $relativePath" -ForegroundColor Yellow
                }
                
                if ($Fix) {
                    # 补全package关键字
                    $content = "p" + $content.TrimStart()
                    [System.IO.File]::WriteAllText($filePath, $content, [System.Text.UTF8Encoding]::new($false))
                    $fixedCount++
                    Write-Host "[FIXED] 已补全package: $relativePath" -ForegroundColor Green
                }
            }
        }
        
        # 检查3: 中文乱码检测（简单的启发式检查）
        if (!$hasIssue -and $bytes.Length -gt 100) {
            $content = [System.Text.Encoding]::UTF8.GetString($bytes)
            
            # 检查是否包含常见的乱码模式
            $garbledPatterns = @(
                "鏁版嵁", "鐢ㄦ埛", "瀵嗙爜", "绠＄悊", "鎿嶄綔",
                "搴撳垵濮嬪寲", "鑾峰彇", "淇濆瓨", "鍒犻櫎", "鏌ヨ",
                "\u9332", "\u7490", "\u641c"
            )
            
            foreach ($pattern in $garbledPatterns) {
                if ($content -match $pattern) {
                    $garbledCount++
                    $hasIssue = $true
                    
                    if ($Verbose) {
                        Write-Host "[GARBLED] 可能存在乱码: $relativePath" -ForegroundColor Red
                    }
                    break
                }
            }
        }
        
    } catch {
        Write-Host "[ERROR] 无法读取文件: $relativePath" -ForegroundColor Red
    }
}

# 输出统计结果
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  检查完成！" -ForegroundColor Cyan
Write-Host "----------------------------------------" -ForegroundColor Gray
Write-Host "  扫描文件数: $totalCount" -ForegroundColor White
Write-Host "  BOM问题文件: $bomCount" -ForegroundColor $(if ($bomCount -gt 0) { "Red" } else { "Green" })
Write-Host "  首字母缺失: $missingFirstChar" -ForegroundColor $(if ($missingFirstChar -gt 0) { "Red" } else { "Green" })
Write-Host "  疑似乱码文件: $garbledCount" -ForegroundColor $(if ($garbledCount -gt 0) { "Red" } else { "Green" })
Write-Host "  已修复文件数: $fixedCount" -ForegroundColor $(if ($fixedCount -gt 0) { "Green" } else { "White" })
Write-Host "========================================" -ForegroundColor Cyan

if ($bomCount -eq 0 -and $missingFirstChar -eq 0 -and $garbledCount -eq 0) {
    Write-Host ""
    Write-Host "✓ 所有文件编码正常，未发现问题！" -ForegroundColor Green
} elseif (-not $Fix) {
    Write-Host ""
    Write-Host "提示: 使用 -Fix 参数可自动修复发现的问题" -ForegroundColor Yellow
    Write-Host "示例: .\check-encoding.ps1 -Fix" -ForegroundColor Yellow
}
