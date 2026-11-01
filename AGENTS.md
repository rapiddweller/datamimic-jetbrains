# Working on this repository

## Writing standard: ASD-STE100

All English text follows ASD-STE100 (Simplified Technical English) at about 80 %. Use its rules, and keep the text natural.

This applies to issues, pull request texts, commit messages, ADRs, docs, the changelog, and code comments.
It does not apply to code, identifiers, commands, and quoted error text.
New and changed text follows the standard. Do not rewrite old text only to match it.

Rules:

1. Write one idea in each sentence. Steps: 20 words or fewer. Descriptions: 25 words or fewer.
2. Use the active voice and the present tense. Write "The bridge closes the socket", not "The socket is closed".
3. Use "must" for a requirement and "can" for a possibility. Do not use "would", "could", "may" or "should".
4. Describe a past bug in the simple past: "The window returned to Welcome". Do not write "could return".
5. State real doubt in its own line that starts with "Not verified:". Say what is unknown.
6. Use one word for one thing. Do not write pairs with a slash, such as "close/dispose". Write "close and dispose".
7. Define a technical term once, or use a plain word. Use "use", not "utilize". Do not use idioms or marketing words.
8. Keep the articles (a, an, the). Do not stack more than three nouns.
9. Write steps as a numbered list, one action each, in the imperative. Put a warning before the step it affects.
10. Keep a paragraph to six sentences or fewer. Use lists and tables for facts.
11. Write command, flag and file names exactly, in code font.

## Issues and pull requests

1. Start from the template in `.github`. Keep its headings, so every issue and pull request reads the same way.
2. Write the title in the imperative: "Reconnect the language server". Keep it short.
3. Set a label: `bug`, `enhancement` or `documentation`.
4. Link the issue in the pull request with `Closes #<number>`. Make one pull request for one issue.
5. Split the verification into "Verified locally" and "Not verified". Never imply a test ran when it did not.
6. Add a Mermaid diagram when the change affects a state, a flow or a lifecycle.
