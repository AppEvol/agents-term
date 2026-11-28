import subprocess
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

INSTALLER = Path(__file__).resolve().parents[1] / "install-git-hooks.sh"


class GitHooksTest(unittest.TestCase):
    def git(self, cwd, *args, **kwargs):
        return subprocess.run(["git", *args], cwd=cwd, text=True, capture_output=True, check=True, **kwargs)

    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        self.remote = root / "remote"
        self.main = root / "main checkout"
        self.feature = root / "feature"
        self.git(root, "init", "--bare", "-b", "main", self.remote)
        # Import fixture history so these tests also run without a configured Git identity.
        for revision in ("one", "two"):
            parent = "" if revision == "one" else "from refs/heads/main^0\n"
            self.git(
                self.remote,
                "fast-import",
                input=f"commit refs/heads/main\ncommitter Fixture <fixture@localhost> 1 +0000\ndata 3\n{revision}\n"
                f"{parent}M 100644 inline version\ndata 3\n{revision}\n\ndone\n",
            )
        self.git(root, "clone", self.remote, self.main)
        self.git(self.main, "reset", "--hard", "HEAD^")
        self.git(self.main, "worktree", "add", "-b", "feature", self.feature)
        subprocess.run([INSTALLER], cwd=self.feature, check=True, capture_output=True)

    def push(self, name, worktree=None):
        return self.git(worktree or self.feature, "push", "origin", f"HEAD:refs/heads/{name}")

    def version(self):
        return (self.main / "version").read_text()

    def test_parallel_pushes_and_commit_guard(self):
        blocked = subprocess.run(["git", "hook", "run", "pre-commit"], cwd=self.main, capture_output=True, text=True)
        self.assertNotEqual(blocked.returncode, 0)
        self.assertIn("Direct commits", blocked.stderr)
        self.git(self.feature, "hook", "run", "pre-commit")
        second = self.main.parent / "second"
        self.git(self.feature, "worktree", "add", "-b", "second", second)
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(self.push, ("first", "second"), (self.feature, second)))
        self.assertEqual(self.version(), "two")
        self.assertEqual((self.feature / "version").read_text(), "one")
        self.assertFalse(self.git(self.main, "status", "--porcelain").stdout)

    def test_skips_dirty_busy_and_diverged_main(self):
        dirty = self.main / "untracked"
        dirty.touch()
        self.assertIn("local changes", self.push("dirty").stderr)
        dirty.unlink()
        lock = self.main / ".git" / "index.lock"
        lock.touch()
        self.assertIn("operation in progress", self.push("busy").stderr)
        lock.unlink()
        self.assertEqual(self.version(), "one")
        self.git(
            self.main,
            "fast-import",
            input="commit refs/heads/diverged\ncommitter Fixture <fixture@localhost> 2 +0000\ndata 3\nnew\n"
            "from refs/heads/main^0\n\ndone\n",
        )
        self.git(self.main, "merge", "--ff-only", "diverged")
        self.assertIn("could not fast-forward", self.push("diverged").stderr)
        self.assertEqual(self.version(), "one")

    def test_sync_when_main_is_not_checked_out(self):
        self.git(self.main, "switch", "--detach")
        self.push("detached")
        self.assertEqual(self.git(self.main, "show", "main:version").stdout, "two")
        self.assertEqual(self.version(), "one")
