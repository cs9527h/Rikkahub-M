"""Regression for GitHub's job-level expression context (validated by a real 422 response)."""
import pathlib
import unittest


class UpstreamWorkflowContextTest(unittest.TestCase):
    def test_runner_context_is_not_used_in_job_environment(self):
        root = pathlib.Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/upstream-review.yml').read_text()
        job_setup, steps = workflow.split('    steps:', 1)
        self.assertNotIn('${{ runner.', job_setup)
        self.assertIn("printf 'REVIEW_OUTPUT=%s/upstream-review\\n'", steps)
        self.assertIn('"$RUNNER_TEMP" >> "$GITHUB_ENV"', steps)
        self.assertIn('--output-dir "$REVIEW_OUTPUT"', steps)
        self.assertIn('if: always()', steps)


if __name__ == '__main__':
    unittest.main()
