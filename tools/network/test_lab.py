import unittest
from unittest.mock import patch
import lab


class MobileProfileTest(unittest.TestCase):
    def test_mobile_models_variable_delay_loss_and_asymmetric_rate(self):
        with patch.object(lab, 'run') as run:
            lab.shape('mobile-jitter')
        commands = [call.args for call in run.call_args_list]
        self.assertEqual(2, len(commands))
        for command in commands:
            args = list(command)
            self.assertEqual(['60ms', '25ms', 'distribution', 'normal'], args[args.index('delay')+1:args.index('delay')+5])
            self.assertIn('loss', args)
            self.assertIn('0.3%', args)
        self.assertIn('3mbit', commands[0])
        self.assertIn('12mbit', commands[1])

    def test_unknown_profile_fails_without_changing_qdisc(self):
        with patch.object(lab, 'run') as run:
            with self.assertRaises(ValueError): lab.shape('typo')
            run.assert_not_called()

    def test_mobile_seed_is_reproducible_in_both_directions(self):
        with patch.object(lab, 'run') as run:
            lab.shape('mobile-jitter', seed=526)
        commands = [list(call.args) for call in run.call_args_list]
        self.assertEqual('526', commands[0][commands[0].index('seed') + 1])
        self.assertEqual('527', commands[1][commands[1].index('seed') + 1])


if __name__ == '__main__': unittest.main()
