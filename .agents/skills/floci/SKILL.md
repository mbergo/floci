```markdown
# floci Development Patterns

> Auto-generated skill from repository analysis

## Overview
This skill introduces the core development patterns and conventions used in the `floci` TypeScript codebase. It covers file organization, code style, commit practices, and testing patterns, providing practical guidance and command suggestions for efficient project contribution.

## Coding Conventions

### File Naming
- Use **camelCase** for all file names.
  - Example: `myComponent.ts`, `userService.test.ts`

### Import Style
- Use **relative imports** for referencing other modules.
  - Example:
    ```typescript
    import { myFunction } from './utils';
    ```

### Export Style
- Use **named exports** exclusively.
  - Example:
    ```typescript
    // utils.ts
    export function myFunction() { ... }
    ```

### Commit Messages
- Follow **conventional commit** format.
- Use the `feat` prefix for new features.
  - Example:
    ```
    feat: add user authentication module
    ```

## Workflows

### Feature Development
**Trigger:** When implementing a new feature  
**Command:** `/feature-development`

1. Create a new branch for your feature.
2. Implement the feature in camelCase-named files.
3. Use relative imports and named exports.
4. Write or update tests in `*.test.*` files.
5. Commit changes using the `feat` prefix and a concise message.
6. Open a pull request for review.

### Testing
**Trigger:** Before pushing or merging code  
**Command:** `/run-tests`

1. Locate or create test files matching the `*.test.*` pattern.
2. Run the test suite using the project's test runner (framework unknown; check project scripts).
3. Ensure all tests pass before merging.

## Testing Patterns

- Test files are named with the pattern `*.test.*` (e.g., `userService.test.ts`).
- The testing framework is not specified; check for scripts or documentation in the repository.
- Place tests alongside the modules they cover or in a dedicated test directory.

## Commands
| Command                | Purpose                                 |
|------------------------|-----------------------------------------|
| /feature-development   | Guide for starting a new feature branch |
| /run-tests             | Run the test suite                      |
```
