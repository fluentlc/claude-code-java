---
name: git-commit
description: Create well-structured git commits with conventional commit messages
tags: git, vcs, commit
---

# Git Commit Skill

When the user asks you to commit changes, follow this workflow:

1. Run `git status` to see what files have changed
2. Run `git diff --staged` to review staged changes (or `git diff` for unstaged)
3. Analyze the changes and determine the commit type:
   - feat: A new feature
   - fix: A bug fix
   - docs: Documentation changes
   - refactor: Code refactoring
   - test: Adding or updating tests
   - chore: Build process or auxiliary tool changes
4. Write a clear commit message following Conventional Commits format:
   ```
   type(scope): short description

   Longer description if needed.
   ```
5. Stage the relevant files with `git add`
6. Create the commit with `git commit -m "message"`

## Important Rules
- Never use `git add .` without reviewing changes first
- Always check for sensitive files (.env, credentials) before committing
- Keep the subject line under 72 characters
