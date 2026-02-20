import os
import json
from github import Github
from openai import OpenAI

# 1. 获取环境变量
github_token = os.environ.get("GITHUB_TOKEN")
api_key = os.environ.get("CUSTOM_API_KEY")
base_url = os.environ.get("CUSTOM_BASE_URL")
# 这里填入你要调用的模型名字
model_name = os.environ.get("CUSTOM_MODEL", "deepseek-coder")

# 2. 初始化兼容 OpenAI 的 Client
client = OpenAI(
    api_key=api_key,
    base_url=base_url # 关键：通过传入 base_url 将请求劫持到第三方大模型
)

# 3. 初始化 GitHub 客户端
g = Github(github_token)

# 4. 从 GitHub Actions 环境变量中读取触发事件的上下文
event_path = os.environ.get("GITHUB_EVENT_PATH")
with open(event_path, "r") as f:
    event_data = json.load(f)

# 获取仓库名和 Issue 信息
repo_name = event_data
issue_number = event_data
issue_title = event_data
issue_body = event_data

# 5. 构建 Prompt，调用兼容 AI大模型
system_prompt = "你是一个高级 AI 程序员（Agent）。请阅读用户的 Issue 描述，并直接给出解决这个问题的完整代码或修改建议。"
user_prompt = f"Issue 标题: {issue_title}\n\nIssue 详情:\n{issue_body}\n\n请提供代码解决方案："

print(f"正在请求 {model_name}...")
response = client.chat.completions.create(
    model=model_name,
    messages=,
    temperature=0.1 # 代码生成建议用低温度
)

ai_reply = response.choices.message.content

# 6. 将 AI 的回复通过 GitHub API 写回 Issue 评论区
repo = g.get_repo(repo_name)
issue = repo.get_issue(number=issue_number)
issue.create_comment(f"🤖 **AI Agent (基于 {model_name}) 解决方案：**\n\n{ai_reply}")

print("✅ Agent 执行完毕，已回复 Issue。")