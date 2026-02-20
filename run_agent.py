import os
import json
import sys
from github import Github, Auth
from openai import OpenAI

# 1. 物理参数提取
github_token = os.environ.get("GITHUB_TOKEN")
api_key = os.environ.get("CUSTOM_API_KEY")
base_url = os.environ.get("CUSTOM_BASE_URL")
# 针对 LongCat-Flash-Thinking-2601 模型进行适配
model_name = os.environ.get("CUSTOM_MODEL", "LongCat-Flash-Thinking-2601")

# 2. 存在性调查
if not all([github_token, api_key, base_url]):
    print("错误：核心环境变量（TOKEN/KEY/URL）缺失。")
    sys.exit(1)

# 3. 初始化 OpenAI 兼容客户端
client = OpenAI(
    api_key=api_key,
    base_url=base_url
)

# 4. 初始化 GitHub 物理链接（使用最新的 Auth 方式消除警告）
auth = Auth.Token(github_token)
g = Github(auth=auth)

# 5. 读取 Issue 原始数据
event_path = os.environ.get("GITHUB_EVENT_PATH")
if not event_path:
    print("错误：未检测到 GITHUB_EVENT_PATH")
    sys.exit(1)

with open(event_path, "r", encoding="utf-8") as f:
    event_data = json.load(f)

repo_name = event_data.get("repository", {}).get("full_name")
issue_number = event_data.get("issue", {}).get("number")
issue_title = event_data.get("issue", {}).get("title", "无标题")
issue_body = event_data.get("issue", {}).get("body", "无内容")

# 6. 构造 Prompt
system_prompt = "你是一个基于唯物辩证法的 AI 程序员。请分析 Issue 的主要矛盾，给出直击痛点、具备工程落地价值的代码方案。"
user_prompt = f"仓库: {repo_name}\nIssue 标题: {issue_title}\n详情: {issue_body}\n\n请给出具体的修复代码或操作序列："

print(f"执行状态：正在向 {base_url} 请求模型 {model_name} ...")

# 7. 模型请求与异常处理
try:
    response = client.chat.completions.create(
        model=model_name,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt}
        ],
        temperature=0.1
    )
    # 【核心修正】：必须通过索引 [0] 访问 choices 列表
    ai_reply = response.choices[0].message.content
except Exception as e:
    print(f"API 调用阶段发生本质错误: {str(e)}")
    # 打印更多调试信息，如果是 API 返回格式异样，可以从这里观察
    sys.exit(1)

# 8. 反馈回写
try:
    repo = g.get_repo(repo_name)
    issue = repo.get_issue(number=issue_number)
    comment_body = f"🤖 **AI Agent ({model_name}) 分析建议：**\n\n{ai_reply}"
    issue.create_comment(comment_body)
    print(f"成功：已在 Issue #{issue_number} 中发布解决方案。")
except Exception as e:
    print(f"GitHub 回写阶段失败: {e}")
    sys.exit(1)