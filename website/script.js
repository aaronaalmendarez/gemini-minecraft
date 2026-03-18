// Tab switching for commands section
document.addEventListener('DOMContentLoaded', () => {
    const tabBtns = document.querySelectorAll('.tab-btn');
    const tabContents = document.querySelectorAll('.tab-content');

    tabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            const target = btn.dataset.tab;

            tabBtns.forEach(b => b.classList.remove('active'));
            tabPanes.forEach(p => p.classList.remove('active'));

            btn.classList.add('active');
            document.getElementById(target).classList.add('active');
        });
    });

    // Smooth scroll for nav links
    document.querySelectorAll('a[href^="#"]').forEach(anchor => {
        anchor.addEventListener('click', function (e) {
            e.preventDefault();
            const target = document.querySelector(this.getAttribute('href'));
            if (target) {
                target.scrollIntoView({ behavior: 'smooth', block: 'start' });
            }
        });
    });

    /* ===== Scroll Reveal Animations ===== */
    const observerOptions = {
        threshold: 0.1,
        rootMargin: '0px 0px -50px 0px'
    };

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.style.opacity = '1';
                entry.target.style.transform = 'translateY(0)';
                observer.unobserve(entry.target);
            }
        });
    }, observerOptions);

    document.querySelectorAll('.feature-card, .audience-card, .step').forEach(el => {
        el.style.opacity = '0';
        el.style.transform = 'translateY(30px)';
        el.style.transition = 'all 0.6s cubic-bezier(0.23, 1, 0.32, 1)';
        observer.observe(el);
    });

    /* ===== Terminal Rotation Logic ===== */
    const demoSlides = document.querySelectorAll('.demo-slide');
    const termDots = document.querySelectorAll('.term-dot');
    let currentSlide = 0;
    let slideInterval;

    function showSlide(index) {
        demoSlides.forEach(s => s.classList.remove('active'));
        termDots.forEach(d => d.classList.remove('active'));

        demoSlides[index].classList.add('active');
        termDots[index].classList.add('active');
        currentSlide = index;
    }

    function nextSlide() {
        let next = (currentSlide + 1) % demoSlides.length;
        showSlide(next);
    }

    function startAutoSlide() {
        slideInterval = setInterval(nextSlide, 6000);
    }

    function resetAutoSlide() {
        clearInterval(slideInterval);
        startAutoSlide();
    }

    termDots.forEach((dot, index) => {
        dot.addEventListener('click', () => {
            showSlide(index);
            resetAutoSlide();
        });
    });

    if (demoSlides.length > 0) {
        startAutoSlide();
    }

    // Copy command on click with visual feedback
    document.querySelectorAll('.command-item code, .code-block, .example-card code').forEach(code => {
        code.style.cursor = 'pointer';

        code.addEventListener('click', async (e) => {
            e.stopPropagation();
            const text = code.textContent;
            try {
                await navigator.clipboard.writeText(text);

                // Create floating notification
                const notification = document.createElement('div');
                notification.textContent = '✓ Copied!';
                notification.style.cssText = `
                    position: fixed;
                    top: 20px;
                    right: 20px;
                    padding: 12px 24px;
                    background: linear-gradient(135deg, #a855f7, #ec4899);
                    color: white;
                    border-radius: 100px;
                    font-weight: 700;
                    font-size: 0.9rem;
                    z-index: 9999;
                    animation: slideInRight 0.4s ease, fadeOut 0.4s ease 1.5s forwards;
                    box-shadow: 0 8px 32px rgba(168, 85, 247, 0.4);
                `;
                document.body.appendChild(notification);

                setTimeout(() => notification.remove(), 2000);
            } catch (err) {
                console.error('Failed to copy:', err);
            }
        });
    });

    // Add keyframe animations
    const animations = document.createElement('style');
    animations.textContent = `
        @keyframes slideInRight {
            from { transform: translateX(100px); opacity: 0; }
            to { transform: translateX(0); opacity: 1; }
        }
        @keyframes fadeOut {
            to { opacity: 0; transform: translateY(-10px); }
        }
    `;
    document.head.appendChild(animations);

    // Parallax effect on hero
    window.addEventListener('scroll', () => {
        const scrolled = window.pageYOffset;
        const hero = document.querySelector('.hero-content');
        const terminal = document.querySelector('.terminal');
        if (hero && scrolled < 800) {
            hero.style.transform = `translateY(${scrolled * 0.2}px)`;
            hero.style.opacity = 1 - (scrolled / 800);
        }
        if (terminal && scrolled < 600) {
            terminal.style.transform = `perspective(1000px) rotateX(${2 - scrolled * 0.01}deg) translateY(${scrolled * 0.1}px)`;
        }
    });

    // Mouse glow effect on cards
    document.querySelectorAll('.feature-card, .mode-card, .example-card, .step').forEach(card => {
        card.addEventListener('mousemove', (e) => {
            const rect = card.getBoundingClientRect();
            const x = e.clientX - rect.left;
            const y = e.clientY - rect.top;
            card.style.setProperty('--mouse-x', `${x}px`);
            card.style.setProperty('--mouse-y', `${y}px`);
        });
    });

    // Add mouse glow styles
    const glowStyle = document.createElement('style');
    glowStyle.textContent = `
        .feature-card::after,
        .mode-card::after,
        .example-card::after,
        .step::after {
            content: '';
            position: absolute;
            top: 0;
            left: 0;
            right: 0;
            bottom: 0;
            background: radial-gradient(
                600px circle at var(--mouse-x) var(--mouse-y),
                rgba(168, 85, 247, 0.1),
                transparent 40%
            );
            pointer-events: none;
            opacity: 0;
            transition: opacity 0.3s;
            border-radius: inherit;
        }
        .feature-card:hover::after,
        .mode-card:hover::after,
        .example-card:hover::after,
        .step:hover::after {
            opacity: 1;
        }
    `;
    document.head.appendChild(glowStyle);

    // Typing effect for hero title
    const heroTitle = document.querySelector('.hero h1');
    if (heroTitle) {
        heroTitle.style.opacity = '1';
    }

    console.log('🚀 Gemini AI Chat website loaded with premium effects!');
});

// Particle background (lightweight)
function createParticles() {
    const canvas = document.createElement('canvas');
    canvas.id = 'particles';
    canvas.style.cssText = `
        position: fixed;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        pointer-events: none;
        z-index: -1;
    `;
    document.body.prepend(canvas);

    const ctx = canvas.getContext('2d');
    let particles = [];

    function resize() {
        canvas.width = window.innerWidth;
        canvas.height = window.innerHeight;
    }
    resize();
    window.addEventListener('resize', resize);

    for (let i = 0; i < 50; i++) {
        particles.push({
            x: Math.random() * canvas.width,
            y: Math.random() * canvas.height,
            vx: (Math.random() - 0.5) * 0.3,
            vy: (Math.random() - 0.5) * 0.3,
            size: Math.random() * 2 + 1,
            opacity: Math.random() * 0.5 + 0.1
        });
    }

    function animate() {
        ctx.clearRect(0, 0, canvas.width, canvas.height);

        particles.forEach(p => {
            p.x += p.vx;
            p.y += p.vy;

            if (p.x < 0) p.x = canvas.width;
            if (p.x > canvas.width) p.x = 0;
            if (p.y < 0) p.y = canvas.height;
            if (p.y > canvas.height) p.y = 0;

            ctx.beginPath();
            ctx.arc(p.x, p.y, p.size, 0, Math.PI * 2);
            ctx.fillStyle = `rgba(168, 85, 247, ${p.opacity})`;
            ctx.fill();
        });

        requestAnimationFrame(animate);
    }
    animate();
}

// Initialize particles after load
window.addEventListener('load', createParticles);
