import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8081').replace(/\/$/, '');
const COURSE_ID = Number(__ENV.COURSE_ID || 900001);
const PLAN_ID = Number(__ENV.PLAN_ID || 3);
const TOKENS_FILE = __ENV.TOKENS_FILE || '../build/loadtest/paddle-test-users.json';
const RUN_ID = __ENV.RUN_ID || `paddle-slow-${Date.now()}`;
const PRICE_RATE = Number(__ENV.PRICE_RATE || 20);
const PADDLE_RATE = Number(__ENV.PADDLE_RATE || 2);
const PADDLE_START = __ENV.PADDLE_START || '10s';
const PADDLE_DURATION = __ENV.PADDLE_DURATION || '60s';
const PRICE_DURATION = __ENV.PRICE_DURATION || '100s';

const testUsers = JSON.parse(open(TOKENS_FILE));

const priceDuration = new Trend('price_latest_duration', true);
const paddleDuration = new Trend('paddle_transaction_duration', true);

export const options = {
  scenarios: {
    price_latest: {
      executor: 'constant-arrival-rate',
      exec: 'priceLatest',
      rate: PRICE_RATE,
      timeUnit: '1s',
      duration: PRICE_DURATION,
      preAllocatedVUs: 600,
      maxVUs: 800,
      gracefulStop: '90s',
    },
    paddle_transaction: {
      executor: 'constant-arrival-rate',
      exec: 'paddleTransaction',
      rate: PADDLE_RATE,
      timeUnit: '1s',
      startTime: PADDLE_START,
      duration: PADDLE_DURATION,
      preAllocatedVUs: 150,
      maxVUs: 150,
      gracefulStop: '90s',
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
  tags: {
    test_type: 'paddle_slow_upstream',
    run_id: RUN_ID,
  },
};

function isLocalTarget() {
  return /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/i.test(BASE_URL);
}

export function setup() {
  if (!isLocalTarget()) {
    fail('This test drives a slow Paddle mock. Run it only against localhost.');
  }
  if (!Array.isArray(testUsers) || testUsers.length === 0) {
    fail('TOKENS_FILE must contain dedicated test users.');
  }
  const price = http.get(`${BASE_URL}/api/v1/courses/${COURSE_ID}/prices/latest`);
  if (price.status !== 200) {
    fail(`Price preflight failed: HTTP ${price.status}`);
  }
}

export function priceLatest() {
  const response = http.get(`${BASE_URL}/api/v1/courses/${COURSE_ID}/prices/latest`, {
    tags: { endpoint: 'price_latest' },
    timeout: '60s',
  });
  priceDuration.add(response.timings.duration);
  check(response, {
    'price returns 200': (res) => res.status === 200,
  });
}

export function paddleTransaction() {
  const index = exec.scenario.iterationInTest;
  if (index >= testUsers.length) {
    fail(`Not enough test users: need more than ${testUsers.length}.`);
  }
  const user = testUsers[index];
  const response = http.post(
    `${BASE_URL}/api/v1/subscriptions/paddle/transactions?planId=${PLAN_ID}`,
    null,
    {
      headers: {
        Authorization: `Bearer ${user.accessToken}`,
        Accept: 'application/json',
        'X-Load-Test-Run-Id': RUN_ID,
      },
      tags: { endpoint: 'paddle_transaction' },
      timeout: '60s',
    },
  );
  paddleDuration.add(response.timings.duration);
  check(response, {
    'paddle returns a response': (res) => res.status !== 0,
  });
}
