# Contributing

Thanks for helping build Tennis Video Intelligence.

## Before opening a pull request

1. Keep changes focused and document behaviour that affects metric definitions.
2. Add tests for API behaviour, coordinate transforms, event parsing and statistical calculations.
3. Do not commit match footage, personal data, model weights or generated datasets.
4. Record source, licence and intended use before adding a dataset, model or external service.
5. Never label an inferred event as directly observed.

## Backend checks

```bash
cd backend
pip install -e ".[dev]"
ruff check .
ruff format --check .
pytest
```

## Pull request expectations

Describe the problem, design, tests, expected impact on accuracy/coverage and known limitations. For model or metric changes, include before/after evaluation results on a match-level held-out set.
