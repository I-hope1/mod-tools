import json
import os
import sys

from github import Github
from openai import OpenAI

# 1. 物理参数提取
github_token = os.environ.get("GITHUB_TOKEN")
api_key = os.environ.get("CUSTOM_API_KEY")
base_url = os.environ.get("CUSTOM_BASE_URL")
model_name = os.environ.get("CUSTOM_MODEL", "gpt-4o")

# 2. 存在性调查：确保核心变量非空
if not all([github_token, api_key, base_url]):
    print("错误：核心环境变量（TOKEN/KEY/URL）缺失。请检查 GitHub Secrets 配置。")
    sys.exit(1)

# 3. 初始化 OpenAI 兼容客户端
client = OpenAI(api_key=api_key, base_url=base_url)

# 4. 初始化 GitHub 物理链接
g = Github(github_token)

# 5. 读取 Issue 原始数据（调查研究）
event_path = os.environ.get("GITHUB_EVENT_PATH")
with open(event_path, "r", encoding="utf-8") as f:
    event_data = json.load(f)

repo_name = event_data.get("repository", {}).get("full_name")
issue_number = event_data.get("issue", {}).get("number")
issue_title = event_data.get("issue", {}).get("title", "无标题")
issue_body = event_data.get("issue", {}).get("body", "无内容")

# 6. 构造矛盾分析 Prompt
system_prompt = "你是一个基于唯物辩证法的 AI 程序员。请分析 Issue 的主要矛盾，给出直击痛点、具备工程落地价值的代码方案。"
user_prompt = f"仓库: {repo_name}\nIssue 标题: {issue_title}\n详情: {issue_body}\n\n请给出具体操作序列："

print(f"执行状态：正在调用 {model_name} ...")

# 7. 模型请求（此处已修复语法：明确传入 messages 列表）
try:
    response = client.chat.completions.create(
        model=model_name,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        temperature=0.1,
    )
    ai_reply = response.choices.message.content
except Exception as e:
    print(f"API 调用阶段发生本质错误: {e}")
    sys.exit(1)

# 8. 成果扬弃：将 AI 建议反馈至 GitHub
try:
    repo = g.get_repo(repo_name)
    issue = repo.get_issue(number=issue_number)
    issue.create_comment(f"🤖 **AI Agent ({model_name}) 深度分析建议：**\n\n{ai_reply}")
    print("执行完毕：反馈已成功送达。")
except Exception as e:
    print(f"GitHub 回写阶段失败，请检查 GITHUB_TOKEN 写入权限: {e}")
    sys.exit(1)
