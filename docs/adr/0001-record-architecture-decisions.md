# 0001. Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-11-07
- **Recorded:** retroactively, after implementation (the decision itself was taken on 2026-11-07, after v1.0 and
  v2.0 had been built; ADRs 0002–0021 describe decisions that were taken while v1.0 and v2.0 were built)

## Context

Stark VMS v1.0 (Assignment 1) and v2.0 (Assignment 2) were designed and built without a written decision log.
The reasons behind the architecture were scattered over the READMEs ("Decisions and trade-offs", "Known
limitations"), code comments, `docs/API_CONTRACT.md` and `docs/PERFORMANCE.md`. A reviewer, a new team member or
the author a few months later cannot see in one place *what* was decided, *why*, *what else was considered* and
*what it costs*.

The project guideline asks for documented trade-offs in several places (BR-10 "Choose one and document it",
section 13 "mention the trade-off in your README", section 16.3 code standards, section 17 review checklist).

## Decision

We keep Architecture Decision Records in `docs/adr/`, one Markdown file per decision, in the Michael Nygard
format extended with the MADR sections "Alternatives considered" and "Related".

- File name `NNNN-kebab-case-title.md`, numbers never reused. The same decision has the same number in
  Assignment 1 and Assignment 2, so the two folders line up.
- Each ADR has: Title, Status, Date, Recorded, Context, Decision, Alternatives considered (with the reason for
  rejecting each), Consequences (positive, negative, follow-ups) and Related (guideline section, FR/BR IDs,
  files).
- Every factual statement about the system cites the file it comes from.
- **The existing ADRs were written after the code.** They say so in their header ("Recorded: retroactively").
  They reconstruct decisions from the code; they are not evidence of up-front design.
- **From now on an ADR is written before implementation.** A new significant decision starts as
  `Status: Proposed` (copy [`template.md`](template.md)), is reviewed with the pull request that implements it
  and becomes `Accepted` when merged. "Significant" means: a new dependency or external service, a new bounded
  context or a change to who owns a table, a change to the API contract that is not purely additive, a security
  or data-retention rule, or anything that is hard to undo.
- An accepted ADR is not rewritten. When a decision changes, a new ADR supersedes it, and the old one gets
  `Status: Superseded by NNNN`. Typos and broken file references may be fixed in place.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| Keep decisions only in the README "Decisions and trade-offs" sections | One bullet per decision has no room for alternatives or consequences, and the README mixes decisions with run instructions. The README bullets stay as a short summary and link here. |
| Wiki or external document | Not versioned with the code; cannot be reviewed in the same pull request as the change. |
| Do not record retroactively, start ADRs only for future decisions | The most important decisions (bounded contexts, data ownership, auth, storage) are already made; future ADRs would have nothing to refer to. |
| Write the retroactive ADRs as if they had been written up front | Dishonest; a reader would draw wrong conclusions about the process. |

## Consequences

**Positive**
- One place that explains the architecture and its trade-offs, linked from every README.
- Known gaps and accepted deviations from the guideline are visible ([0015](0015-non-functional-requirements-strategy.md),
  [0017](0017-user-account-management-scope.md), [0018](0018-accepted-deviations-from-the-guideline.md)).

**Negative / accepted trade-offs**
- Retroactive ADRs can only record the alternatives that the code, comments and READMEs show; alternatives that
  were considered and forgotten are lost.
- ADRs must be kept in step with the code. A stale ADR is worse than none.

**Follow-ups**
- Add an "ADR needed?" line to the pull-request self-review (guideline section 17.2 checklist).

## Related

- Guideline: sections 16 (Git workflow, pull requests), 17.2 (review checklist), 12 BR-10.
- Files: [`README.md`](README.md) (index), [`template.md`](template.md).
