<?php
/**
 * web-demo 演示接口
 *
 * GET /api.php            -> 返回全部演示数据
 * GET /api.php?type=active -> 仅返回状态为「启用」的记录
 *
 * 说明：纯演示，数据内置于本文件，无数据库。
 */

// 统一输出 JSON
header('Content-Type: application/json; charset=utf-8');

// 演示数据（随便填的数据，用于看效果）
$data = [
    ['id' => 1,  'name' => '张三', 'role' => '前端工程师', 'status' => '启用', 'score' => 92],
    ['id' => 2,  'name' => '李四', 'role' => '后端工程师', 'status' => '启用', 'score' => 88],
    ['id' => 3,  'name' => '王五', 'role' => '产品经理',   'status' => '停用', 'score' => 76],
    ['id' => 4,  'name' => '赵六', 'role' => '测试工程师', 'status' => '启用', 'score' => 84],
    ['id' => 5,  'name' => '孙七', 'role' => 'UI 设计师',  'status' => '停用', 'score' => 81],
    ['id' => 6,  'name' => '周八', 'role' => '运维工程师', 'status' => '启用', 'score' => 90],
];

// 参数白名单校验：仅接受枚举值，防止注入
$type = isset($_GET['type']) ? strtolower(trim((string) $_GET['type'])) : '';
if ($type !== '' && $type !== 'active') {
    http_response_code(400);
    echo json_encode(
        ['ok' => false, 'error' => "参数 type 仅支持空值或 'active'，收到: " . $type],
        JSON_UNESCAPED_UNICODE
    );
    exit;
}

// 过滤
if ($type === 'active') {
    $data = array_values(array_filter($data, fn($row) => $row['status'] === '启用'));
}

// 响应
echo json_encode(
    ['ok' => true, 'total' => count($data), 'data' => $data],
    JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT
);
