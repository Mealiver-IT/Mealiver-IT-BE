import React, { useState, useEffect, useRef } from 'react';

/**
 * 쿠폰 재고 실시간 카운트다운 대시보드
 *
 * 지금은 가짜(랜덤) 데이터로 재고가 줄어드는 것처럼 보여줌.
 * 나중에 Redis 실제 값과 연결할 때는 아래 useEffect 안의
 * setInterval 부분을 SSE/WebSocket 연결로 교체하면 됨.
 *
 * 사용법:
 *   <CouponStockDashboard totalStock={10000} />
 */
export default function CouponStockDashboard({ totalStock = 10000 }) {
  const [remaining, setRemaining] = useState(totalStock);
  const [rate, setRate] = useState(0);
  const [status, setStatus] = useState('발급 진행 중');
  const timerRef = useRef(null);

  useEffect(() => {
    // ── 지금: 가짜 데이터로 흉내 ──────────────────────────
    // 나중에 진짜로 바꿀 때는 이 블록을 아래 주석 예시로 교체
    timerRef.current = setInterval(() => {
      setRemaining((prev) => {
        if (prev <= 0) {
          clearInterval(timerRef.current);
          setStatus('재고 소진 (품절)');
          return 0;
        }
        const drop = Math.floor(Math.random() * 40) + 10;
        const next = Math.max(0, prev - drop);
        setRate(drop);
        return next;
      });
    }, 800);

    return () => clearInterval(timerRef.current);

    // ── 나중에: 진짜 Redis 값 (SSE 예시) ──────────────────
    // const eventSource = new EventSource('/api/campaigns/1/stock-stream');
    // eventSource.onmessage = (e) => {
    //   const data = JSON.parse(e.data); // { remaining: number, rate: number }
    //   setRemaining(data.remaining);
    //   setRate(data.rate);
    //   if (data.remaining <= 0) setStatus('재고 소진 (품절)');
    // };
    // return () => eventSource.close();
  }, []);

  const percent = Math.round(((totalStock - remaining) / totalStock) * 100);
  const isSoldOut = remaining === 0;

  return (
    <div style={{ maxWidth: 680, fontFamily: 'sans-serif' }}>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(3, 1fr)',
          gap: 12,
          marginBottom: 24,
        }}
      >
        <StatCard label="총 재고" value={totalStock.toLocaleString()} />
        <StatCard label="남은 재고" value={remaining.toLocaleString()} />
        <StatCard label="초당 발급" value={rate.toLocaleString()} />
      </div>

      <div
        style={{
          background: '#fafafa',
          border: '1px solid #e5e5e5',
          borderRadius: 12,
          padding: 20,
          marginBottom: 16,
        }}
      >
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            fontSize: 13,
            color: '#666',
            marginBottom: 8,
          }}
        >
          <span>재고 소진율</span>
          <span>{percent}%</span>
        </div>
        <div
          style={{
            height: 12,
            background: '#eee',
            borderRadius: 6,
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              height: '100%',
              width: `${percent}%`,
              background: isSoldOut ? '#d64545' : '#2563eb',
              transition: 'width 0.3s',
            }}
          />
        </div>
      </div>

      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          fontSize: 13,
          color: '#666',
        }}
      >
        <span
          style={{
            width: 8,
            height: 8,
            borderRadius: '50%',
            background: isSoldOut ? '#d64545' : '#22c55e',
            display: 'inline-block',
          }}
        />
        <span>{status}</span>
      </div>
    </div>
  );
}

function StatCard({ label, value }) {
  return (
    <div
      style={{
        background: '#f5f5f5',
        borderRadius: 8,
        padding: 16,
      }}
    >
      <p style={{ fontSize: 13, color: '#666', margin: '0 0 4px' }}>{label}</p>
      <p style={{ fontSize: 24, fontWeight: 500, margin: 0 }}>{value}</p>
    </div>
  );
}
