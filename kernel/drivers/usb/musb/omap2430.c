/*
 * Copyright (C) 2005-2007 by Texas Instruments
 * Some code has been taken from tusb6010.c
 * Copyrights for that are attributable to:
 * Copyright (C) 2006 Nokia Corporation
 * Tony Lindgren <tony@atomide.com>
 *
 * This file is part of the Inventra Controller Driver for Linux.
 *
 * The Inventra Controller Driver for Linux is free software; you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License version 2 as published by the Free Software
 * Foundation.
 *
 * The Inventra Controller Driver for Linux is distributed in
 * the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public
 * License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with The Inventra Controller Driver for Linux ; if not,
 * write to the Free Software Foundation, Inc., 59 Temple Place,
 * Suite 330, Boston, MA  02111-1307  USA
 *
 */
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/sched.h>
#include <linux/init.h>
#include <linux/list.h>
#include <linux/clk.h>
#include <linux/io.h>
#include <linux/platform_device.h>
#include <linux/dma-mapping.h>

#include "musb_core.h"
#include "omap2430.h"

#ifdef CONFIG_PM
struct musb *gb_musb;
unsigned short musb_clock_on = 1;
void omap2430_idle_save_context(void);
void omap2430_idle_restore_context(void);
#endif

struct omap2430_glue {
	struct device		*dev;
	struct platform_device	*musb;
	struct clk		*clk;
};
#define glue_to_musb(g)		platform_get_drvdata(g->musb)

static struct timer_list musb_idle_timer;
#define  POLL_SECONDS    2

static void omap2430_musb_id_change(struct musb *musb)
{
	if (is_otg_enabled(musb) && musb->xceiv->state == OTG_STATE_B_IDLE)
		mod_timer(&musb_idle_timer, jiffies + POLL_SECONDS * HZ);
}
static void musb_do_idle(unsigned long _musb)
{
	struct musb	*musb = (void *)_musb;
	unsigned long	flags;
#ifdef CONFIG_USB_MUSB_HDRC_HCD
	u8	power;
#endif
	u8	devctl;

	spin_lock_irqsave(&musb->lock, flags);

	switch (musb->xceiv->state) {
	case OTG_STATE_A_WAIT_BCON:
		devctl = musb_readb(musb->mregs, MUSB_DEVCTL);
		devctl &= ~MUSB_DEVCTL_SESSION;
		musb_writeb(musb->mregs, MUSB_DEVCTL, devctl);

		devctl = musb_readb(musb->mregs, MUSB_DEVCTL);
		if (devctl & MUSB_DEVCTL_HM) {
			musb->xceiv->state = OTG_STATE_A_IDLE;
			MUSB_HST_MODE(musb);
		} else {
			musb->xceiv->state = OTG_STATE_B_IDLE;
			MUSB_DEV_MODE(musb);
			mod_timer(&musb_idle_timer,
					jiffies + POLL_SECONDS * HZ);
		}
		break;
#ifdef CONFIG_USB_MUSB_HDRC_HCD
	case OTG_STATE_A_SUSPEND:
		/* finish RESUME signaling? */
		if (musb->port1_status & MUSB_PORT_STAT_RESUME) {
			power = musb_readb(musb->mregs, MUSB_POWER);
			power &= ~MUSB_POWER_RESUME;
			DBG(1, "root port resume stopped, power %02x\n", power);
			musb_writeb(musb->mregs, MUSB_POWER, power);
			musb->is_active = 1;
			musb->port1_status &= ~(USB_PORT_STAT_SUSPEND
						| MUSB_PORT_STAT_RESUME);
			musb->port1_status |= USB_PORT_STAT_C_SUSPEND << 16;
			usb_hcd_poll_rh_status(musb_to_hcd(musb));
			/* NOTE: it might really be A_WAIT_BCON ... */
			musb->xceiv->state = OTG_STATE_A_HOST;
		}
		break;
#endif
#ifdef CONFIG_USB_MUSB_HDRC_HCD
	case OTG_STATE_A_HOST:
		devctl = musb_readb(musb->mregs, MUSB_DEVCTL);
		if (devctl &  MUSB_DEVCTL_HM)
			musb->xceiv->state = OTG_STATE_A_WAIT_BCON;
		else
			musb->xceiv->state = OTG_STATE_B_IDLE;
#endif
	case OTG_STATE_B_IDLE:
		if (!is_peripheral_enabled(musb))
			break;

		devctl = musb_readb(musb->mregs, MUSB_DEVCTL);
		if (devctl & MUSB_DEVCTL_HM) {
			musb->xceiv->state = OTG_STATE_A_HOST;
		} else {
			mod_timer(&musb_idle_timer,
					jiffies + POLL_SECONDS * HZ);
			musb_writeb(musb->mregs, MUSB_DEVCTL, devctl |
				MUSB_DEVCTL_SESSION);
		}
		break;
	default:
		break;
	}
	spin_unlock_irqrestore(&musb->lock, flags);
}

#define MUSB_TIMEOUT_A_WAIT_BCON	1100

static void omap2430_musb_try_idle(struct musb *musb, unsigned long timeout)
{
	unsigned long		default_timeout = jiffies + msecs_to_jiffies(3);
	static unsigned long	last_timer;

	if (timeout == 0)
		timeout = default_timeout;

	/* Never idle if active, or when VBUS timeout is not set as host */
	if (musb->is_active || ((musb->a_wait_bcon == 0)
			&& (musb->xceiv->state == OTG_STATE_A_WAIT_BCON))) {
		DBG(4, "%s active, deleting timer\n", otg_state_string(musb));
		del_timer(&musb_idle_timer);
		last_timer = jiffies;
		return;
	}

	if (time_after(last_timer, timeout)) {
		if (!timer_pending(&musb_idle_timer))
			last_timer = timeout;
		else {
			DBG(4, "Longer idle timer already pending, ignoring\n");
			return;
		}
	}
	last_timer = timeout;

	DBG(4, "%s inactive, for idle timer for %lu ms\n",
		otg_state_string(musb),
		(unsigned long)jiffies_to_msecs(timeout - jiffies));
	mod_timer(&musb_idle_timer, timeout);
}

static void omap2430_musb_set_vbus(struct musb *musb, int is_on)
{
	u8		devctl;
	unsigned long timeout = jiffies + msecs_to_jiffies(1000);
	int ret = 1;
	/* HDRC controls CPEN, but beware current surges during device
	 * connect.  They can trigger transient overcurrent conditions
	 * that must be ignored.
	 */

	devctl = musb_readb(musb->mregs, MUSB_DEVCTL);

	if (is_on) {
		if (musb->xceiv->state == OTG_STATE_A_IDLE) {
			/* start the session */
			devctl |= MUSB_DEVCTL_SESSION;
			musb_writeb(musb->mregs, MUSB_DEVCTL, devctl);
			/*
			 * Wait for the musb to set as A device to enable the
			 * VBUS
			 */
			while (musb_readb(musb->mregs, MUSB_DEVCTL) & 0x80) {

				cpu_relax();

				if (time_after(jiffies, timeout)) {
					dev_err(musb->controller,
					"configured as A device timeout");
					ret = -EINVAL;
					break;
				}
			}

			if (ret && musb->xceiv->set_vbus)
				otg_set_vbus(musb->xceiv, 1);
		} else {
			musb->is_active = 1;
			musb->xceiv->default_a = 1;
			musb->xceiv->state = OTG_STATE_A_WAIT_VRISE;
			devctl |= MUSB_DEVCTL_SESSION;
			MUSB_HST_MODE(musb);
		}
	} else {
		musb->is_active = 0;

		/* NOTE:  we're skipping A_WAIT_VFALL -> A_IDLE and
		 * jumping right to B_IDLE...
		 */

		musb->xceiv->default_a = 0;
		musb->xceiv->state = OTG_STATE_B_IDLE;
		devctl &= ~MUSB_DEVCTL_SESSION;

		MUSB_DEV_MODE(musb);
	}
	musb_writeb(musb->mregs, MUSB_DEVCTL, devctl);

	DBG(1, "VBUS %s, devctl %02x "
		/* otg %3x conf %08x prcm %08x */ "\n",
		otg_state_string(musb),
		musb_readb(musb->mregs, MUSB_DEVCTL));
}

static int omap2430_musb_set_mode(struct musb *musb, u8 musb_mode)
{
	u8	devctl = musb_readb(musb->mregs, MUSB_DEVCTL);

	devctl |= MUSB_DEVCTL_SESSION;
	musb_writeb(musb->mregs, MUSB_DEVCTL, devctl);

	return 0;
}

static inline void omap2430_low_level_exit(struct musb *musb)
{
	u32 l;

	/* in any role */
	l = musb_readl(musb->mregs, OTG_FORCESTDBY);
	l |= ENABLEFORCE;	/* enable MSTANDBY */
	musb_writel(musb->mregs, OTG_FORCESTDBY, l);

	l = musb_readl(musb->mregs, OTG_SYSCONFIG);
	l |= ENABLEWAKEUP;	/* enable wakeup */
	musb_writel(musb->mregs, OTG_SYSCONFIG, l);
}

static inline void omap2430_low_level_init(struct musb *musb)
{
	u32 l;

	l = musb_readl(musb->mregs, OTG_SYSCONFIG);
	l &= ~ENABLEWAKEUP;	/* disable wakeup */
	musb_writel(musb->mregs, OTG_SYSCONFIG, l);

	l = musb_readl(musb->mregs, OTG_FORCESTDBY);
	l &= ~ENABLEFORCE;	/* disable MSTANDBY */
	musb_writel(musb->mregs, OTG_FORCESTDBY, l);
}

/* blocking notifier support */
static int musb_otg_notifications(struct notifier_block *nb,
		unsigned long event, void *unused)
{
	struct musb	*musb = container_of(nb, struct musb, nb);
	struct device *dev = musb->controller;
	struct musb_hdrc_platform_data *pdata = dev->platform_data;
	struct omap_musb_board_data *data = pdata->board_data;

	switch (event) {
	case USB_EVENT_ID:
		DBG(4, "ID GND\n");

		if (is_otg_enabled(musb)) {
#ifdef CONFIG_USB_GADGET_MUSB_HDRC
			if (musb->gadget_driver) {
				otg_init(musb->xceiv);

				if (data->interface_type ==
						MUSB_INTERFACE_UTMI)
					omap2430_musb_set_vbus(musb, 1);

			}
#endif
		} else {
			otg_init(musb->xceiv);
			if (data->interface_type ==
					MUSB_INTERFACE_UTMI)
				omap2430_musb_set_vbus(musb, 1);
		}
		break;

	case USB_EVENT_VBUS:
		DBG(4, "VBUS Connect\n");

		otg_init(musb->xceiv);
		break;

	case USB_EVENT_NONE:
		DBG(4, "VBUS Disconnect\n");

		if (data->interface_type == MUSB_INTERFACE_UTMI) {
			if (musb->xceiv->set_vbus)
				otg_set_vbus(musb->xceiv, 0);
		}
		otg_shutdown(musb->xceiv);
		break;
	default:
		DBG(4, "ID float\n");
		return NOTIFY_DONE;
	}

	return NOTIFY_OK;
}

static int omap2430_musb_init(struct musb *musb)
{
	u32 l, status = 0;
	struct device *dev = musb->controller;
	struct musb_hdrc_platform_data *plat = dev->platform_data;
	struct omap_musb_board_data *data = plat->board_data;

	/* We require some kind of external transceiver, hooked
	 * up through ULPI.  TWL4030-family PMICs include one,
	 * which needs a driver, drivers aren't always needed.
	 */
	musb->xceiv = otg_get_transceiver(musb->id);
	if (!musb->xceiv) {
		pr_err("HS USB OTG: no transceiver configured\n");
		return -ENODEV;
	}

	omap2430_low_level_init(musb);

	l = musb_readl(musb->mregs, OTG_SYSCONFIG);
	l &= ~ENABLEWAKEUP;	/* disable wakeup */
	l &= ~NOSTDBY;		/* remove possible nostdby */
	l |= SMARTSTDBY;	/* enable smart standby */
	l &= ~AUTOIDLE;		/* disable auto idle */
	l &= ~NOIDLE;		/* remove possible noidle */

	/* SMARTIDLE is blocking core to enter off mode in 3630 */
	if (cpu_is_omap3630())
		l |= FORCEIDLE;		/* enable force idle */
	else
		l |= SMARTIDLE;		/* enable smart idle */
	/*
	 * MUSB AUTOIDLE don't work in 3430.
	 * Workaround by Richard Woodruff/TI
	 */
	if (!cpu_is_omap3430())
		l |= AUTOIDLE;		/* enable auto idle */
	musb_writel(musb->mregs, OTG_SYSCONFIG, l);

	l = musb_readl(musb->mregs, OTG_INTERFSEL);

	if (data->interface_type == MUSB_INTERFACE_UTMI) {
		/* OMAP4 uses Internal PHY GS70 which uses UTMI interface */
		l &= ~ULPI_12PIN;       /* Disable ULPI */
		l |= UTMI_8BIT;         /* Enable UTMI  */
	} else {
		l |= ULPI_12PIN;
	}

	musb_writel(musb->mregs, OTG_INTERFSEL, l);

	pr_debug("HS USB OTG: revision 0x%x, sysconfig 0x%02x, "
			"sysstatus 0x%x, intrfsel 0x%x, simenable  0x%x\n",
			musb_readl(musb->mregs, OTG_REVISION),
			musb_readl(musb->mregs, OTG_SYSCONFIG),
			musb_readl(musb->mregs, OTG_SYSSTATUS),
			musb_readl(musb->mregs, OTG_INTERFSEL),
			musb_readl(musb->mregs, OTG_SIMENABLE));

	musb->nb.notifier_call = musb_otg_notifications;
	status = otg_register_notifier(musb->xceiv, &musb->nb);

	if (status)
		DBG(1, "notification register failed\n");

	/* check whether cable is already connected */
	if (musb->xceiv->state ==OTG_STATE_B_IDLE)
		musb_otg_notifications(&musb->nb, 1,
					musb->xceiv->gadget);

	musb->a_wait_bcon = MUSB_TIMEOUT_A_WAIT_BCON;
	setup_timer(&musb_idle_timer, musb_do_idle, (unsigned long) musb);

#ifdef CONFIG_PM
	gb_musb = musb;
	omap_musb_save = omap2430_idle_save_context;
	omap_musb_restore = omap2430_idle_restore_context;
#endif
	return 0;
}

static int omap2430_musb_exit(struct musb *musb)
{
	del_timer_sync(&musb_idle_timer);
	otg_unregister_notifier(musb->xceiv, &musb->nb);
	omap2430_low_level_exit(musb);
	otg_put_transceiver(musb->xceiv);

#ifdef CONFIG_PM
	omap_musb_save = NULL;
	omap_musb_restore = NULL;
#endif
	return 0;
}

static const struct musb_platform_ops omap2430_ops = {
	.fifo_mode	= 4,
	.flags		= MUSB_GLUE_EP_ADDR_FLAT_MAPPING | MUSB_GLUE_DMA_INVENTRA,
	.init		= omap2430_musb_init,
	.exit		= omap2430_musb_exit,

	.set_mode	= omap2430_musb_set_mode,
	.try_idle	= omap2430_musb_try_idle,

	.set_vbus	= omap2430_musb_set_vbus,
	.id_poll	= omap2430_musb_id_change,

	.read_fifo	= musb_read_fifo,
	.write_fifo	= musb_write_fifo,

	.dma_controller_create = inventra_dma_controller_create,
	.dma_controller_destroy = inventra_dma_controller_destroy,
};

static u64 omap2430_dmamask = DMA_BIT_MASK(32);

static int __init omap2430_probe(struct platform_device *pdev)
{
	struct musb_hdrc_platform_data	*pdata = pdev->dev.platform_data;
	struct platform_device		*musb;
	struct omap2430_glue		*glue;
	struct clk			*clk;

	int				ret = -ENOMEM;

	glue = kzalloc(sizeof(*glue), GFP_KERNEL);
	if (!glue) {
		dev_err(&pdev->dev, "failed to allocate glue context\n");
		goto err0;
	}

	musb = platform_device_alloc("musb-hdrc", pdev->id);
	if (!musb) {
		dev_err(&pdev->dev, "failed to allocate musb device\n");
		goto err1;
	}

	dev_set_name(&pdev->dev, "musb-omap2430");
	clk = clk_get(&pdev->dev, "ick");
	if (IS_ERR(clk)) {
		dev_err(&pdev->dev, "failed to get clock\n");
		ret = PTR_ERR(clk);
		goto err2;
	}

	ret = clk_enable(clk);
	if (ret) {
		dev_err(&pdev->dev, "failed to enable clock\n");
		goto err3;
	}

	musb->dev.parent		= &pdev->dev;
	musb->dev.dma_mask		= &omap2430_dmamask;
	musb->dev.coherent_dma_mask	= omap2430_dmamask;

	glue->dev			= &pdev->dev;
	glue->musb			= musb;
	glue->clk			= clk;

	pdata->platform_ops		= &omap2430_ops;

	platform_set_drvdata(pdev, glue);

	ret = platform_device_add_resources(musb, pdev->resource,
			pdev->num_resources);
	if (ret) {
		dev_err(&pdev->dev, "failed to add resources\n");
		goto err4;
	}

	ret = platform_device_add_data(musb, pdata, sizeof(*pdata));
	if (ret) {
		dev_err(&pdev->dev, "failed to add platform_data\n");
		goto err4;
	}

	ret = platform_device_add(musb);
	if (ret) {
		dev_err(&pdev->dev, "failed to register musb device\n");
		goto err4;
	}

	return 0;

err4:
	clk_disable(clk);

err3:
	clk_put(clk);

err2:
	platform_device_put(musb);

err1:
	kfree(glue);

err0:
	return ret;
}

static int __exit omap2430_remove(struct platform_device *pdev)
{
	struct omap2430_glue		*glue = platform_get_drvdata(pdev);

	platform_device_del(glue->musb);
	platform_device_put(glue->musb);
	clk_disable(glue->clk);
	clk_put(glue->clk);
	kfree(glue);

	return 0;
}

#ifdef CONFIG_PM
static void omap2430_save_context(struct musb *musb)
{
	musb->context.otg_sysconfig = musb_readl(musb->mregs, OTG_SYSCONFIG);
	musb->context.otg_forcestandby = musb_readl(musb->mregs, OTG_FORCESTDBY);
}

static void omap2430_restore_context(struct musb *musb)
{
	musb_writel(musb->mregs, OTG_SYSCONFIG, musb->context.otg_sysconfig);
	musb_writel(musb->mregs, OTG_FORCESTDBY, musb->context.otg_forcestandby);
}

void omap2430_idle_save_context(void)
{
	struct musb *musb = gb_musb;

	if (!musb_clock_on)
		return;

	musb_save_context(musb);
	omap2430_save_context(musb);
}
void omap2430_idle_restore_context(void)
{
	struct musb *musb = gb_musb;

	if (!musb_clock_on)
		return;

	omap2430_restore_context(musb);
	musb_restore_context(musb);
}

static int omap2430_suspend(struct device *dev)
{
	struct omap2430_glue		*glue = dev_get_drvdata(dev);
	struct musb			*musb = glue_to_musb(glue);

	if (!musb_clock_on)
		return 0;

	omap2430_low_level_exit(musb);
// {SW} BEGIN: To avoid kernel halt during system resume	
	//otg_set_suspend(musb->xceiv, 1);
	msleep(20);
// {SW} END:	
	musb_save_context(musb);
	omap2430_save_context(musb);
	clk_disable(glue->clk);
	musb_clock_on = 0;

	return 0;
}

static int omap2430_resume(struct device *dev)
{
	struct omap2430_glue		*glue = dev_get_drvdata(dev);
	struct musb			*musb = glue_to_musb(glue);
	int				ret;

	if (musb_clock_on)
		return 0;

	ret = clk_enable(glue->clk);
	if (ret) {
		dev_err(dev, "faled to enable clock\n");
		return ret;
	}

	musb_clock_on = 1;
	omap2430_restore_context(musb);
	omap2430_low_level_init(musb);
	musb_restore_context(musb);
	
// {SW} BEGIN: To avoid kernel halt during system resume	
	//otg_set_suspend(musb->xceiv, 0);
	msleep(20);
// {SW} END:

	return 0;
}

static struct dev_pm_ops omap2430_pm_ops = {
	.suspend	= omap2430_suspend,
	.resume		= omap2430_resume,
};

#define DEV_PM_OPS	(&omap2430_pm_ops)
#else
#define DEV_PM_OPS	NULL
#endif

static struct platform_driver omap2430_driver = {
	.remove		= __exit_p(omap2430_remove),
	.driver		= {
		.name	= "musb-omap2430",
		.pm	= DEV_PM_OPS,
	},
};

MODULE_DESCRIPTION("OMAP2PLUS MUSB Glue Layer");
MODULE_AUTHOR("Felipe Balbi <balbi@ti.com>");
MODULE_LICENSE("GPL v2");

static int __init omap2430_init(void)
{
	return platform_driver_probe(&omap2430_driver, omap2430_probe);
}
subsys_initcall(omap2430_init);

static void __exit omap2430_exit(void)
{
	platform_driver_unregister(&omap2430_driver);
}
module_exit(omap2430_exit);
