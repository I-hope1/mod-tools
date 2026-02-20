import os
import json
from github import Github
from openai import OpenAI

# 1. 获取环境变量
github_token = os.environ.get("GITHUB_TOKEN")
api_key = os.environ.get("CUSTOM_API_KEY")
base_url = os.environ.get("CUSTOM_BASE_URL")
model_name = os.environ.get("CUSTOM_MODEL", "gpt-4o") # 对应 longcat 平台的模型名

# 2. 初始化兼容 OpenAI 的 Client
client = OpenAI(
    api_key=api_key,
    base_url=base_url
)

# 3. 初始化 GitHub 客户端
g = Github(github_token)

# 4. 从 GitHub Actions 环境变量中读取触发事件的上下文
event_path = os.environ.get("GITHUB_EVENT_PATH")
with open(event_path, "r", encoding="utf-8") as f:
    event_data = json.load(f)

# 【修复 1】正确解析 GitHub Action 传过来的 JSON 数据
repo_name = event_data.get("repository", {}).get("full_name")
issue_number = event_data.get("issue", {}).get("number")
issue_title = event_data.get("issue", {}).get("title", "无标题")
issue_body = event_data.get("issue", {}).get("body", "无内容")

if not issue_number or not repo_name:
    print("未检测到有效的 Issue 触发，退出。")
    exit(0)

# 5. 构建 Prompt，调用兼容 AI 大模型
system_prompt = "你是一个高级 AI 程序员（Agent）。请阅读用户的 Issue 描述，并直接给出解决这个问题的完整代码或修改建议。"
user_prompt = f"Issue 标题: {issue_title}\n\nIssue 详情:\n{issue_body}\n\n请提供代码解决方案："

print(f"正在请求模型: {model_name}...")

# 【修复 2】补全 messages 参数，传入标准的 OpenAI 消息格式
response = client.chat.completions.create(
    model=model_name,
    messages=,
    temperature=0.1 # 代码生成建议用低温度
)

# 获取 AI 返回的内容
ai_reply = response.choices.message.content

# 6. 将 AI 的回复通过 GitHub API 写回 Issue 评论区
repo = g.get_repo(repo_name)
issue = repo.get_issue(number=issue_number)
issue.create_comment(f"🤖 **AI Agent (基于 {model_name}) 解决方案：**\n\n{ai_reply}")

print("✅ Agent 执行完毕，已成功回复 Issue。")