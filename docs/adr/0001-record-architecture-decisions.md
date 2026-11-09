# 0001. Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken on 2026-11-07, after v1.0 had
  been built; the other ADRs in this folder describe decisions that were taken while v1.0 was built)

## Context

Stark VMS v1.0 (Assignment 1) was designed and built without a written decision log. The reasons behind the
architecture are spread over the READMEs ("Decisions and trade-offs", "Known limitations"), code comments,
`docs/API_CONTRACT.md` and `docs/PERFORMANCE.md`. A reviewer, a new team member or the author a few months later
cannot see in one place *what* was decided, *why*, *what else was considered* and *what it costs*.

The project guideline asks for documented trade-offs in several places: BR-10 "Choose one and document it",
section 13 "mention the trade-off in your README", the code standards in section 16.3, and the review checklist
in section 17.2.

## Decision

We keep Architecture Decision Records in `docs/adr/`, one Markdown file per decision, in the Michael Nygard
format extended with the MADR sections "Alternatives considered" and "Related".

- **File names and numbers**
  - The file name is `NNNN-kebab-case-title.md`, and a number is never reused.
  - The same decision has the same number in Assignment 1 and Assignment 2, so the two folders line up.
  - This folder therefore has gaps. 0012–0014 and 0017–0019 record v2.0 decisions (the BR-10 delete policy,
    document storage, driver assignment, user-management scope, guideline deviations, dashboard read model). They
    exist only in `assignment 2/docs/adr/`.
- **Sections:** each ADR has Title, Status, Date, Recorded, Context, Decision, Alternatives considered (with the
  reason for rejecting each), Consequences (positive, negative, follow-ups) and Related (guideline section, FR/BR
  IDs, files).
- **Evidence:** every factual statement about the system cites the file it comes from. The statements describe
  *this* project's code (v1.0), not Assignment 2.
- **The existing ADRs were written after the code.** Their header says so ("Recorded: retroactively"). They
  reconstruct decisions from the code; they are not evidence of up-front design.
- **From now on an ADR is written before implementation.**
  - A new significant decision starts as `Status: Proposed` (copy [`template.md`](template.md)). It is reviewed
    with the pull request that implements it and becomes `Accepted` when merged.
  - "Significant" means:
    - a new dependency or external service;
    - a new bounded context, or a change to who owns a table;
    - a change to the API contract that is not purely additive;
    - a security or data-retention rule;
    - anything that is hard to undo.
- **Changing a decision:** an accepted ADR is not rewritten. A new ADR supersedes it, and the old one gets
  `Status: Superseded by NNNN`. Typos and broken file references may be fixed in place.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Keep decisions only in the README "Decisions and trade-offs" sections | One bullet per decision has no room for alternatives or consequences, and the README mixes decisions with run instructions. The README bullets stay as a short summary and link here. |
| Wiki or external document | Not versioned with the code; cannot be reviewed in the same pull request as the change. |
| Do not record retroactively, start ADRs only for future decisions | The most important decisions (contexts, auth, database, error format) are already made; future ADRs would have nothing to refer to. |
| Write the retroactive ADRs as if they had been written up front | Dishonest; a reader would draw wrong conclusions about the process. |

## Consequences

**Positive**
- One place that explains the v1.0 architecture and its trade-offs, linked from every README.
- The numbering lines up with Assignment 2, so a reader can see how each decision was extended in v2.0.

**Negative / accepted trade-offs**
- Retroactive ADRs can only record the alternatives that the code, comments and READMEs show. Alternatives that
  were considered and then forgotten are lost.
- ADRs must be kept in step with the code. A stale ADR is worse than none.

**Follow-ups**
- Add an "ADR needed?" line to the pull-request self-review (guideline section 17.2 checklist).

## Related

- Guideline: sections 16 (Git workflow, pull requests), 17.2 (review checklist), 12 BR-10.
- Files: [`README.md`](README.md) (index), [`template.md`](template.md).
